package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.datasource.hikari.schema=quarantine_service_test","spring.flyway.schemas=quarantine_service_test","spring.flyway.default-schema=quarantine_service_test","resume.data-dir=./target/quarantine-service-bootstrap"})
@Transactional
class QuarantineServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired Validator validator;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path data;
    final Instant now=Instant.parse("2026-10-01T01:00:00Z"),old=now.minus(Duration.ofDays(31));
    final MutableClock clock=new MutableClock(now);
    WorkspaceGate gate; QuarantineStore store; StoragePreviewService preview;
    BackupService backups; QuarantineService service; ResumeService resumes; LocalFileStorage files;
    @BeforeEach void setup(){
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("quarantine_service_test");
        jdbc.execute("TRUNCATE document_imports,job_reports,version_assets,resume_assets,resume_versions,resumes,attachments");
        gate=new WorkspaceGate();files=new LocalFileStorage(data.toString(),mapper);
        resumes=new ResumeService(jdbc,mapper,new AssetCatalog(jdbc,mapper,files));
        store=new QuarantineStore(data,mapper,clock);
        preview=new StoragePreviewService(jdbc,mapper,gate,transactions,data.toString(),store,clock);
        backups=new BackupService(jdbc,mapper,validator,files,gate,transactions,data.toString(),52428800,5242880,24000000);
        service=new QuarantineService(preview,store,backups,gate);
    }
    ImageService.Asset image()throws Exception {
        var asset=new ImageService(files,5242880,24000000).importImage(Files.readAllBytes(Path.of("fixtures/university-logo.png")));
        new AssetCatalog(jdbc,mapper,files).register(asset);
        jdbc.update("UPDATE attachments SET created_at=? WHERE id=?",java.sql.Timestamp.from(old),UUID.fromString(asset.id()));
        try(var paths=Files.list(data.resolve("attachments/"+asset.id()))){for(Path p:paths.toList())Files.setLastModifiedTime(p,FileTime.from(old));}
        Files.setLastModifiedTime(data.resolve("attachments/"+asset.id()),FileTime.from(old));return asset;
    }
    String pdf()throws Exception {
        String id=UUID.randomUUID().toString();Files.createDirectories(data.resolve("exports"));byte[] bytes="%PDF-1.4\nsynthetic quarantine PDF\n%%EOF".getBytes();
        Files.write(data.resolve("exports/"+id+".pdf"),bytes);
        mapper.writeValue(data.resolve("exports/"+id+".json").toFile(),new ExportService.Export(id,UUID.randomUUID().toString(),"a".repeat(64),ImageService.sha(bytes),old,null,null,null));
        for(String ext:List.of("pdf","json"))Files.setLastModifiedTime(data.resolve("exports/"+id+"."+ext),FileTime.from(old));return id;
    }
    QuarantineService.Request request(String operation,String digest,String kind,String id,Boolean confirm){return new QuarantineService.Request(operation,digest,List.of(new QuarantineService.Selection(kind,id)),confirm);}
    QuarantineService.Request request(String kind,String id){return request(UUID.randomUUID().toString(),preview.preview().digest(),kind,id,true);}
    Map<String,String> originals()throws Exception {
        var result=new TreeMap<String,String>();
        for(String folder:List.of("attachments","exports","model-settings")){Path base=data.resolve(folder);if(Files.exists(base))try(var paths=Files.walk(base)){for(Path path:paths.toList())result.put(data.relativize(path).toString(),(Files.isRegularFile(path)?ImageService.sha(Files.readAllBytes(path)):"directory")+":"+Files.getLastModifiedTime(path));}}
        return result;
    }
    void code(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo(code));}
    MockMvc http(){return MockMvcBuilders.standaloneSetup(new StorageController(preview,service)).setControllerAdvice(new ApiErrors()).addFilters(new LocalRequestFilter(gate)).build();}

    // Removing explicit confirmation or the filter's storage exclusion causes moves or a lock upgrade.
    @Test void endpointsRequireConfirmationAndRetainLocalRequestGuards()throws Exception {
        var asset=image();var request=request("image",asset.id());var before=originals();var http=http();
        for(Boolean confirm:Arrays.asList(null,false)){
            http.perform(post("/api/storage/quarantine").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(request(request.operationId(),request.previewDigest(),"image",asset.id(),confirm))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUARANTINE_CONFIRM_REQUIRED"));
            http.perform(post("/api/storage/quarantine/"+request.operationId()+"/restore").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(new QuarantineService.RestoreRequest("a".repeat(64),confirm))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUARANTINE_CONFIRM_REQUIRED"));
        }
        http.perform(post("/api/storage/quarantine").contentType("application/json").content(mapper.writeValueAsBytes(request))).andExpect(status().isForbidden());
        http.perform(post("/api/storage/quarantine").header("X-Local-Resume","1").header("Origin","https://attacker.invalid").contentType("application/json").content(mapper.writeValueAsBytes(request))).andExpect(status().isForbidden());
        assertThat(originals()).isEqualTo(before);assertThat(store.history(0).items()).isEmpty();
    }
    @Test void changedOrUndisplayedOrExpiredDigestNeverReservesOrMoves()throws Exception {
        var asset=image();var displayed=preview.preview();var before=originals();
        code(()->service.quarantine(request(UUID.randomUUID().toString(),"a".repeat(64),"image",asset.id(),true)),"STORAGE_PREVIEW_EXPIRED");
        Path normalized=data.resolve("attachments/"+asset.id()+"/image.png");byte[] changed=Files.readAllBytes(normalized);changed[changed.length-1]^=1;Files.write(normalized,changed);Files.setLastModifiedTime(normalized,FileTime.from(old));
        code(()->service.quarantine(request(UUID.randomUUID().toString(),displayed.digest(),"image",asset.id(),true)),"STORAGE_PREVIEW_CHANGED");
        assertThat(store.history(0).items()).isEmpty();assertThat(Files.isDirectory(data.resolve("attachments/"+asset.id()))).isTrue();
        clock.instant=now.plus(Duration.ofMinutes(10));
        code(()->service.quarantine(request(UUID.randomUUID().toString(),displayed.digest(),"image",asset.id(),true)),"STORAGE_PREVIEW_EXPIRED");
        assertThat(Files.exists(data.resolve("backups"))).isFalse();assertThat(before).hasSameSizeAs(originals());
    }
    @Test void internalFreshScanDoesNotRenewOldDisplayAndOnlyThirtyTwoDigestsStayAuthorized()throws Exception {
        var asset=image();String first=preview.preview().digest();
        for(int n=0;n<32;n++){jdbc.update("UPDATE attachments SET created_at=? WHERE id=?",java.sql.Timestamp.from(old.plusSeconds(n+1)),UUID.fromString(asset.id()));preview.preview();}
        code(()->service.quarantine(request(UUID.randomUUID().toString(),first,"image",asset.id(),true)),"STORAGE_PREVIEW_EXPIRED");
        String last=preview.preview().digest();clock.instant=now.plusSeconds(599);preview.freshSnapshot();clock.instant=now.plusSeconds(600);
        code(()->service.quarantine(request(UUID.randomUUID().toString(),last,"image",asset.id(),true)),"STORAGE_PREVIEW_EXPIRED");assertThat(store.history(0).items()).isEmpty();
    }
    @Test void historicalImageCannotBeSelectedEvenFromAnUnchangedDisplayedReport()throws Exception {
        var asset=image();var plain=ResumeDocument.sample("one");var l=plain.layout();
        var document=new ResumeDocument(plain.schemaVersion(),plain.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,new ResumeDraft.ImageSlot(asset.id(),false,26,34,"cover",0,1,50,50),l.logo(),l.presentation()));
        var source=resumes.create("synthetic history",document);resumes.save(source.id(),new ResumeService.Save(source.title(),plain,1,UUID.randomUUID()));
        var request=request("image",asset.id());var before=originals();
        code(()->service.quarantine(request),"QUARANTINE_NOT_CANDIDATE");assertThat(originals()).isEqualTo(before);assertThat(store.history(0).items()).isEmpty();
    }
    @Test void fullBackupIsVerifiedBeforeMoveAndSameRequestRetryCreatesNoSecondBackup()throws Exception {
        var asset=image();String pdf=pdf();resumes.create("backup content canary",ResumeDocument.sample("one"));
        Files.createDirectories(data.resolve("model-settings"));Files.writeString(data.resolve("model-settings/master.key"),"synthetic secret");
        String tables=jdbc.queryForObject("SELECT jsonb_agg(row_to_json(t))::text FROM (SELECT * FROM attachments ORDER BY id) t",String.class);
        var shown=preview.preview();var request=new QuarantineService.Request(UUID.randomUUID().toString(),shown.digest(),List.of(new QuarantineService.Selection("pdf",pdf),new QuarantineService.Selection("image",asset.id())),true);
        var receipt=service.quarantine(request);assertThat(receipt.state()).isEqualTo("quarantined");assertThat(receipt.items()).hasSize(2);assertThat(backups.download(receipt.backupId())).isRegularFile();
        assertThat(Files.exists(data.resolve("attachments/"+asset.id()))).isFalse();assertThat(Files.exists(data.resolve("exports/"+pdf+".pdf"))).isFalse();
        try(var zip=new java.util.zip.ZipFile(backups.download(receipt.backupId()).toFile())){assertThat(new String(zip.getInputStream(zip.getEntry("workspace.json")).readAllBytes())).contains("backup content canary");assertThat(zip.getEntry("attachments/"+asset.id()+"/original.png")).isNull();}
        clock.instant=now.plusSeconds(1000);var retry=new QuarantineService.Request(request.operationId(),request.previewDigest(),List.of(request.items().get(1),request.items().get(0),request.items().get(1)),true);
        assertThat(service.quarantine(retry)).isEqualTo(receipt);assertThat(backups.history(0).items()).hasSize(1);
        code(()->service.quarantine(new QuarantineService.Request(request.operationId(),request.previewDigest(),request.items(),false)),"QUARANTINE_CONFIRM_REQUIRED");
        code(()->service.quarantine(new QuarantineService.Request(request.operationId(),"b".repeat(64),request.items(),true)),"QUARANTINE_CONFLICT");
        code(()->service.quarantine(request(request.operationId(),request.previewDigest(),"image",asset.id(),true)),"QUARANTINE_CONFLICT");assertThat(backups.history(0).items()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(row_to_json(t))::text FROM (SELECT * FROM attachments ORDER BY id) t",String.class)).isEqualTo(tables);
        assertThat(Files.readString(data.resolve("model-settings/master.key"))).isEqualTo("synthetic secret");
        var held=preview.preview();assertThat(held.items().stream().filter(i->asset.id().equals(i.id())).toList()).singleElement().satisfies(i->assertThat(i.status()).isEqualTo("quarantined"));
        String json=mapper.writeValueAsString(service.history(0));assertThat(json).doesNotContain(data.toString(),"path","sha256","synthetic secret");
        var restored=service.restore(receipt.id(),new QuarantineService.RestoreRequest(receipt.digest(),true));assertThat(restored.state()).isEqualTo("restored");assertThat(service.restore(receipt.id(),new QuarantineService.RestoreRequest(receipt.digest(),true))).isEqualTo(restored);
        assertThat(preview.preview().items().stream().filter(i->asset.id().equals(i.id())).findFirst().orElseThrow().status()).isEqualTo("recent");
    }
    @Test void realBackupFailureKeepsAllOriginalContentsAndTimestampsAndVisibleReservation()throws Exception {
        var asset=image();String pdf=pdf();var request=request("image",asset.id());
        Files.createDirectory(data.resolve("backups"));Files.writeString(data.resolve("backups/blocker"),"canary");
        // A directory occupied by a regular file is a real backup boundary failure.
        Files.delete(data.resolve("backups/blocker"));Files.delete(data.resolve("backups"));Files.writeString(data.resolve("backups"),"backup directory canary");
        var before=originals();code(()->service.quarantine(request),"BACKUP_FAILED");
        assertThat(originals()).isEqualTo(before);var preparing=service.history(0).items().getFirst();assertThat(preparing.state()).isEqualTo("preparing");assertThat(preparing.backupId()).isNull();
        assertThat(service.quarantine(request)).isEqualTo(preparing);assertThat(originals()).isEqualTo(before);assertThat(Files.readString(data.resolve("backups"))).isEqualTo("backup directory canary");
    }
    @Test void corruptNewBackupDownloadBlocksEveryMoveWithoutTouchingOriginals()throws Exception {
        var asset=image();var request=request("image",asset.id());var before=originals();
        var boundary=new QuarantineService.Backup(){public BackupService.Created create(){var created=backups.create();try{Files.writeString(backups.download(created.id()),"corrupt archive");}catch(Exception e){throw new RuntimeException(e);}return created;}public Path download(String id){return backups.download(id);}};
        var faulty=new QuarantineService(preview,store,boundary,gate);
        code(()->faulty.quarantine(request),"BACKUP_CORRUPT");assertThat(originals()).isEqualTo(before);assertThat(service.history(0).items().getFirst().state()).isEqualTo("preparing");
    }
    @Test void partialMoveFailureIsReturnedAsAttentionRatherThanSuccessfulQuarantine()throws Exception {
        var asset=image();String pdf=pdf();
        var failing=new QuarantineStore(data,mapper,clock,(source,destination)->{throw new java.io.IOException("synthetic move failure");});
        var failingService=new QuarantineService(preview,failing,backups,gate);var request=request("image",asset.id());var before=originals();
        var receipt=failingService.quarantine(request);assertThat(receipt.state()).isEqualTo("attention");assertThat(receipt.errorCode()).isEqualTo("QUARANTINE_MOVE_FAILED");assertThat(receipt.backupId()).isNotNull();assertThat(originals()).isEqualTo(before);
    }
    @Test void newlySavedReferenceInvalidatesDisplayedCandidateBeforeReservation()throws Exception {
        var asset=image();var request=request("image",asset.id());var before=originals();var plain=ResumeDocument.sample("one");var l=plain.layout();
        resumes.create("synthetic new reference",new ResumeDocument(plain.schemaVersion(),plain.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,new ResumeDraft.ImageSlot(asset.id(),true,26,34,"cover",0,1,50,50),l.logo(),l.presentation())));
        code(()->service.quarantine(request),"STORAGE_PREVIEW_CHANGED");assertThat(originals()).isEqualTo(before);assertThat(store.history(0).items()).isEmpty();assertThat(Files.exists(data.resolve("backups"))).isFalse();
    }
    @Test void httpRoundTripListsReceiptAndRestoresOnlyWithMatchingDigest()throws Exception {
        var asset=image();var request=request("image",asset.id());var http=http();
        String json=http.perform(post("/api/storage/quarantine").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("quarantined")).andReturn().getResponse().getContentAsString();
        var receipt=mapper.readValue(json,QuarantineStore.Receipt.class);
        http.perform(get("/api/storage/quarantine")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(receipt.id())).andExpect(jsonPath("$.items[0].backupId").value(receipt.backupId()));
        Path held=data.resolve("quarantine/"+receipt.id()+"/payload/attachments/"+asset.id()+"/original.png");String hash=ImageService.sha(Files.readAllBytes(held));
        http.perform(post("/api/storage/quarantine/"+receipt.id()+"/restore").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(new QuarantineService.RestoreRequest("c".repeat(64),true))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUARANTINE_CONFLICT"));assertThat(ImageService.sha(Files.readAllBytes(held))).isEqualTo(hash);
        Files.createDirectory(data.resolve("attachments/"+asset.id()));
        code(()->service.restore(receipt.id(),new QuarantineService.RestoreRequest(receipt.digest(),true)),"QUARANTINE_CONFLICT");assertThat(ImageService.sha(Files.readAllBytes(held))).isEqualTo(hash);
        Files.delete(data.resolve("attachments/"+asset.id()));
        http.perform(post("/api/storage/quarantine/"+receipt.id()+"/restore").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(new QuarantineService.RestoreRequest(receipt.digest(),true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("restored"));assertThat(ImageService.sha(Files.readAllBytes(data.resolve("attachments/"+asset.id()+"/original.png")))).isEqualTo(hash);
    }
    @Test void malformedSelectionsCannotReserveOrBackup()throws Exception {
        var asset=image();String digest=preview.preview().digest();
        for(var items:Arrays.<List<QuarantineService.Selection>>asList(null,List.of(),Arrays.asList((QuarantineService.Selection)null),List.of(new QuarantineService.Selection(null,asset.id())),List.of(new QuarantineService.Selection("other",asset.id())),List.of(new QuarantineService.Selection("image","../bad"))))
            code(()->service.quarantine(new QuarantineService.Request(UUID.randomUUID().toString(),digest,items,true)),"QUARANTINE_INVALID");
        var many=new ArrayList<QuarantineService.Selection>();for(int n=0;n<101;n++)many.add(new QuarantineService.Selection("pdf",UUID.randomUUID().toString()));
        code(()->service.quarantine(new QuarantineService.Request(UUID.randomUUID().toString(),digest,many,true)),"QUARANTINE_INVALID");
        assertThat(service.history(0).items()).isEmpty();assertThat(Files.exists(data.resolve("backups"))).isFalse();
    }
    static class MutableClock extends Clock {
        Instant instant;MutableClock(Instant instant){this.instant=instant;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant;}
    }
}
