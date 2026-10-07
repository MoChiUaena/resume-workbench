package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.atomic.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.datasource.hikari.schema=purge_service_test","spring.flyway.schemas=purge_service_test","spring.flyway.default-schema=purge_service_test","resume.data-dir=./target/purge-service-bootstrap"})
class QuarantinePurgeServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @TempDir Path workspace;
    final Instant now=Instant.parse("2026-10-01T01:00:00Z"),old=now.minus(Duration.ofDays(31));
    final MutableClock clock=new MutableClock(now);
    static final String IMAGE="11111111-1111-4111-8111-111111111111",PDF="22222222-2222-4222-8222-222222222222",OP="33333333-3333-4333-8333-333333333333",BACKUP="44444444-4444-4444-8444-444444444444",RESUME="66666666-6666-4666-8666-666666666666",VERSION="77777777-7777-4777-8777-777777777777";
    Path data,cache; WorkspaceGate gate; QuarantineStore store; QuarantineArchive archive; StoragePreviewService preview; QuarantineService service;
    QuarantineStore.Receipt held; QuarantineFiles.Plan plan; final Map<String,byte[]> contents=new TreeMap<>();
    @BeforeEach void seed()throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("purge_service_test");
        jdbc.execute("TRUNCATE docx_imports,job_reports,version_assets,resume_assets,resume_versions,resumes,attachments");
        data=workspace.resolve("data");cache=workspace.resolve("cache");Files.createDirectories(cache);
        gate=new WorkspaceGate();store=new QuarantineStore(data,mapper,clock);archive=new QuarantineArchive(data,cache,mapper,clock);
        preview=new StoragePreviewService(jdbc,mapper,gate,transactions,data.toString(),store,clock);
        service=service(store,preview,archive);
        contents.put("attachments/"+IMAGE+"/image.png","normalized-canary".getBytes());
        contents.put("attachments/"+IMAGE+"/original.png","original-canary".getBytes());
        contents.put("attachments/"+IMAGE+"/metadata.json",mapper.writeValueAsBytes(new ImageService.Asset(IMAGE,"PNG",contents.get("attachments/"+IMAGE+"/original.png").length,1,1,1,1,1,ImageService.sha(contents.get("attachments/"+IMAGE+"/original.png")),ImageService.sha(contents.get("attachments/"+IMAGE+"/image.png")))));
        contents.put("exports/"+PDF+".pdf","%PDF-original-canary".getBytes());
        contents.put("exports/"+PDF+".json",mapper.writeValueAsBytes(new ExportService.Export(PDF,BACKUP,"a".repeat(64),ImageService.sha(contents.get("exports/"+PDF+".pdf")),old,RESUME,VERSION,3L)));
        var images=new ArrayList<QuarantineFiles.Entry>();var pdfs=new ArrayList<QuarantineFiles.Entry>();
        for(var e:contents.entrySet()){
            Path p=data.resolve(e.getKey());Files.createDirectories(p.getParent());Files.write(p,e.getValue());Files.setLastModifiedTime(p,FileTime.from(old));
            (e.getKey().startsWith("attachments")?images:pdfs).add(new QuarantineFiles.Entry(e.getKey(),e.getValue().length,ImageService.sha(e.getValue()),old));
        }
        plan=new QuarantineFiles.Plan(OP,"a".repeat(64),"b".repeat(64),now,List.of(new QuarantineFiles.Target("image",IMAGE,images.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),images),new QuarantineFiles.Target("pdf",PDF,pdfs.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),pdfs)));
        store.reserve(plan);held=store.moveToQuarantine(OP,BACKUP);
    }
    QuarantineService service(QuarantineStore s,StoragePreviewService p,QuarantineArchive a){return new QuarantineService(p,s,new QuarantineService.Backup(){public BackupService.Created create(){throw new AssertionError("No workspace backup in purge");}public Path download(String id){throw new AssertionError("No workspace backup in purge");}},gate,a,clock);}
    Path payload(){return data.resolve("quarantine/"+OP+"/payload");}
    Path discard(){return data.resolve("quarantine/"+OP+"/discard");}
    void intact()throws Exception{for(var e:contents.entrySet())assertThat(Files.readAllBytes(payload().resolve(e.getKey()))).isEqualTo(e.getValue());assertThat(discard()).doesNotExist();assertThat(store.existing(OP).state()).isEqualTo("quarantined");}
    QuarantineService.FileBackupReceipt ticket(){return service.fileBackup(OP,new QuarantineService.FileBackupRequest(UUID.randomUUID().toString(),held.digest(),true));}
    QuarantineService.PurgeRequest purgeRequest(QuarantineService.FileBackupReceipt r){return new QuarantineService.PurgeRequest(held.digest(),r.id(),r.sha256(),true,true,OP.substring(OP.length()-6));}
    void code(Runnable r,String expected){assertThatThrownBy(r::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo(expected));}
    byte[] deliver(QuarantineService.FileBackupReceipt r)throws Exception{var output=new ByteArrayOutputStream();service.download(OP,r.id()).body().writeTo(output);return output.toByteArray();}
    MockMvc http(){return MockMvcBuilders.standaloneSetup(new StorageController(preview,service)).setControllerAdvice(new ApiErrors()).addFilters(new LocalRequestFilter(gate)).build();}

    // Without delivery/human proof, irreversible Store work must remain unreachable.
    @Test void requiresCompletedStreamAndHumanConfirmationBeforeAnyDeletion()throws Exception {
        var r=ticket();var request=purgeRequest(r);
        code(()->service.purge(OP,request),"QUARANTINE_DOWNLOAD_REQUIRED");intact();
        deliver(r);
        for(Boolean confirm:Arrays.asList(null,false))code(()->service.purge(OP,new QuarantineService.PurgeRequest(held.digest(),r.id(),r.sha256(),confirm,true,"333333")),"QUARANTINE_CONFIRM_REQUIRED");
        code(()->service.purge(OP,new QuarantineService.PurgeRequest(held.digest(),r.id(),r.sha256(),true,false,"333333")),"QUARANTINE_BACKUP_REQUIRED");
        code(()->service.purge(OP,new QuarantineService.PurgeRequest(held.digest(),r.id(),r.sha256(),true,true," 333333")),"QUARANTINE_CONFIRMATION_MISMATCH");intact();
        var done=service.purge(OP,request);assertThat(done.state()).isEqualTo("purged");assertThat(payload()).doesNotExist();assertThat(discard()).doesNotExist();
        assertThat(service.purge(OP,request)).isEqualTo(done);
    }
    @Test void abortedWriteOrFlushNeverAuthorizesPurge()throws Exception {
        var r=ticket();var download=service.download(OP,r.id());
        assertThatThrownBy(()->download.body().writeTo(new OutputStream(){public void write(int b)throws IOException{throw new IOException("abort");}})).isInstanceOf(IOException.class);
        assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isFalse();
        assertThatThrownBy(()->download.body().writeTo(new ByteArrayOutputStream(){public void flush()throws IOException{throw new IOException("flush");}})).isInstanceOf(IOException.class);
        assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isFalse();code(()->service.purge(OP,purgeRequest(r)),"QUARANTINE_DOWNLOAD_REQUIRED");intact();
    }
    @Test void zipDeliveryHasExactEvidenceAndSuccessfulPurgePreservesDatabaseAndModels()throws Exception {
        Files.createDirectories(data.resolve("model-settings"));Files.writeString(data.resolve("model-settings/master.key"),"private-key-canary");
        catalog();String owner=UUID.randomUUID().toString();resume(owner,ResumeDocument.sample("one"));
        jdbc.update("INSERT INTO resume_versions(id,resume_id,title,label,document,source_revision) VALUES (?,?, 'private-history-canary','test',?::jsonb,1)",UUID.randomUUID(),UUID.fromString(owner),mapper.writeValueAsString(ResumeDocument.sample("one")));
        String database=databaseFingerprint();
        var r=ticket();byte[] bytes=deliver(r);assertThat(bytes).hasSize((int)r.bytes());assertThat(ImageService.sha(bytes)).isEqualTo(r.sha256());
        Path saved=workspace.resolve("download.zip");Files.write(saved,bytes);
        try(var zip=new java.util.zip.ZipFile(saved.toFile())){for(var e:contents.entrySet())assertThat(zip.getInputStream(zip.getEntry("files/"+e.getKey())).readAllBytes()).isEqualTo(e.getValue());assertThat(zip.getEntry("workspace.json")).isNull();assertThat(zip.getEntry("model-settings/master.key")).isNull();}
        assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isTrue();
        String json=mapper.writeValueAsString(r);assertThat(json).doesNotContain(data.toString(),cache.toString(),"plan","path","resumeId","versionId","private-key-canary");
        assertThat(service.purge(OP,purgeRequest(r)).state()).isEqualTo("purged");assertThat(Files.readString(data.resolve("model-settings/master.key"))).isEqualTo("private-key-canary");
        assertThat(databaseFingerprint()).isEqualTo(database);assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes",Long.class)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments",Long.class)).isEqualTo(1);assertThat(saved).isRegularFile();
    }
    @Test void completedCleanupIsMergedIntoPublicInventoryAsZeroByteTombstones()throws Exception {
        var r=ticket();deliver(r);service.purge(OP,purgeRequest(r));var report=preview.preview();
        assertThat(report.items()).hasSize(2).allMatch(i->i.status().equals("purged")&&i.reason().equals("purged_file")&&i.bytes()==0);
        assertThat(report.statuses().stream().filter(c->c.key().equals("purged")).findFirst().orElseThrow().count()).isEqualTo(2);
        assertThat(preview.preview().digest()).isEqualTo(report.digest());
    }
    @Test void identitiesConfirmationsAndExpirationAreBoundWithoutGetReclamation()throws Exception {
        String requestId=UUID.randomUUID().toString();
        for(Boolean confirm:Arrays.asList(null,false))code(()->service.fileBackup(OP,new QuarantineService.FileBackupRequest(requestId,held.digest(),confirm)),"QUARANTINE_CONFIRM_REQUIRED");
        var body=new QuarantineService.FileBackupRequest(requestId,held.digest(),true);var r=service.fileBackup(OP,body);
        assertThat(service.fileBackup(OP,body)).isEqualTo(r);
        code(()->service.fileBackup(OP,new QuarantineService.FileBackupRequest(requestId,"c".repeat(64),true)),"QUARANTINE_CONFLICT");
        code(()->service.fileBackup(UUID.randomUUID().toString(),body),"QUARANTINE_CONFLICT");
        code(()->service.purge(OP,new QuarantineService.PurgeRequest(held.digest(),r.id(),"d".repeat(64),true,true,"333333")),"QUARANTINE_CONFLICT");
        code(()->service.fileBackupStatus(OP,"../bad"),"QUARANTINE_INVALID");
        deliver(r);var artifact=archive.create(plan,held.digest());var before=Files.readAllBytes(artifact.path());clock.instant=now.plusSeconds(600);
        assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isFalse();assertThat(Files.readAllBytes(artifact.path())).isEqualTo(before);
        code(()->service.download(OP,r.id()),"QUARANTINE_BACKUP_EXPIRED");code(()->service.purge(OP,purgeRequest(r)),"QUARANTINE_BACKUP_EXPIRED");intact();
    }
    @Test void archiveCreationAndChangedArchiveNeverGrantDownloadProof()throws Exception {
        Path blocked=workspace.resolve("blocked-cache");Files.writeString(blocked,"cache-parent-canary");var faulty=service(store,preview,new QuarantineArchive(data,blocked,mapper,clock));
        code(()->faulty.fileBackup(OP,new QuarantineService.FileBackupRequest(UUID.randomUUID().toString(),held.digest(),true)),"QUARANTINE_CONFLICT");intact();assertThat(Files.readString(blocked)).isEqualTo("cache-parent-canary");
        var r=ticket();var artifact=archive.create(plan,held.digest());Files.writeString(artifact.path(),"changed archive canary");
        assertThatThrownBy(()->deliver(r)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("QUARANTINE_CONFLICT"));
        assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isFalse();code(()->service.purge(OP,purgeRequest(r)),"QUARANTINE_DOWNLOAD_REQUIRED");intact();
    }
    ResumeDocument imageDocument(){var d=ResumeDocument.sample("one");var l=d.layout();return new ResumeDocument(d.schemaVersion(),d.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,new ResumeDraft.ImageSlot(IMAGE,false,26,34,"cover",0,1,50,50),l.logo(),l.presentation()));}
    void resume(String id,ResumeDocument document)throws Exception{jdbc.update("INSERT INTO resumes(id,title,document) VALUES (?, 'private-title-canary', ?::jsonb)",UUID.fromString(id),mapper.writeValueAsString(document));}
    void catalog()throws Exception{jdbc.update("INSERT INTO attachments(id,metadata,created_at) VALUES (?,?::jsonb,?)",UUID.fromString(IMAGE),new String(contents.get("attachments/"+IMAGE+"/metadata.json"),java.nio.charset.StandardCharsets.UTF_8),java.sql.Timestamp.from(old));}
    String databaseFingerprint()throws Exception{var rows=new ArrayList<Object>();for(String table:List.of("attachments","resumes","resume_versions","resume_assets","version_assets"))rows.add(jdbc.queryForList("SELECT to_jsonb(t)::text AS value FROM "+table+" t ORDER BY to_jsonb(t)::text"));return ImageService.sha(mapper.writeValueAsBytes(rows));}
    @ParameterizedTest @ValueSource(strings={"current","historical","broken"})
    void freshHiddenAndHistoricalReferencesBlockDeletionAfterDownload(String kind)throws Exception {
        var r=ticket();deliver(r);catalog();String owner=UUID.randomUUID().toString();
        resume(owner,kind.equals("historical")?ResumeDocument.sample("one"):imageDocument());
        if(kind.equals("current"))jdbc.update("INSERT INTO resume_assets(resume_id,slot,asset_id) VALUES (?,'photo',?)",UUID.fromString(owner),UUID.fromString(IMAGE));
        if(kind.equals("historical")){String version=UUID.randomUUID().toString();jdbc.update("INSERT INTO resume_versions(id,resume_id,title,label,document,source_revision) VALUES (?,?, 'private-version-canary','test',?::jsonb,1)",UUID.fromString(version),UUID.fromString(owner),mapper.writeValueAsString(imageDocument()));jdbc.update("INSERT INTO version_assets(version_id,slot,asset_id) VALUES (?,'photo',?)",UUID.fromString(version),UUID.fromString(IMAGE));}
        code(()->service.purge(OP,purgeRequest(r)),kind.equals("broken")?"QUARANTINE_REFERENCES_UNVERIFIED":"QUARANTINE_REFERENCED");intact();
        assertThat(mapper.writeValueAsString(service.history(0))).doesNotContain("private-title-canary","private-version-canary","resumeId","versionId",data.toString());
    }
    @ParameterizedTest @ValueSource(strings={"resume","version","mismatch","invalid"})
    void freshPdfOwnerOrInvalidReferenceEvidenceBlocksDeletion(String kind)throws Exception {
        var r=ticket();deliver(r);
        if(kind.equals("resume"))resume(RESUME,ResumeDocument.sample("one"));
        else if(kind.equals("invalid")){resume(UUID.randomUUID().toString(),ResumeDocument.sample("one"));jdbc.update("UPDATE resumes SET document='{}'::jsonb");}
        else{String owner=kind.equals("version")?UUID.randomUUID().toString():RESUME;resume(owner,ResumeDocument.sample("one"));jdbc.update("INSERT INTO resume_versions(id,resume_id,title,label,document,source_revision) VALUES (?,?, 'test','test',?::jsonb,99)",UUID.fromString(VERSION),UUID.fromString(owner),mapper.writeValueAsString(ResumeDocument.sample("one")));}
        code(()->service.purge(OP,purgeRequest(r)),kind.equals("invalid")?"QUARANTINE_REFERENCES_UNVERIFIED":"QUARANTINE_REFERENCED");intact();
    }
    @Test void restartLosesInitialTicketsButStartedAndTerminalRequestsUseDurableProof()throws Exception {
        var r=ticket();deliver(r);var request=purgeRequest(r);
        var restarted=service(new QuarantineStore(data,mapper,clock),preview,new QuarantineArchive(data,cache,mapper,clock));
        code(()->restarted.purge(OP,request),"QUARANTINE_BACKUP_NOT_FOUND");intact();
        var deletes=new AtomicInteger();var failing=new QuarantineStore(data,mapper,clock,(a,b)->Files.move(a,b),new QuarantineJournalIo(),p->{if(deletes.incrementAndGet()==5)throw new IOException("actual partial cleanup");Files.delete(p);});
        var faultService=service(failing,preview,archive);var partialTicket=faultService.fileBackup(OP,new QuarantineService.FileBackupRequest(UUID.randomUUID().toString(),held.digest(),true));
        var out=new ByteArrayOutputStream();faultService.download(OP,partialTicket.id()).body().writeTo(out);var partialBody=purgeRequest(partialTicket);
        var pending=faultService.purge(OP,partialBody);assertThat(pending.state()).isEqualTo("purging");assertThat(pending.errorCode()).isEqualTo("QUARANTINE_PURGE_FAILED");assertThat(discard()).isDirectory();
        assertThat(discard().resolve("exports/"+PDF+".json")).doesNotExist();resume(RESUME,ResumeDocument.sample("one"));
        code(()->restarted.purge(OP,partialBody),"QUARANTINE_REFERENCED");assertThat(discard().resolve("exports/"+PDF+".pdf")).isRegularFile();jdbc.update("DELETE FROM resumes WHERE id=?",UUID.fromString(RESUME));
        assertThat(new QuarantineStore(data,mapper,clock).inventory()).anyMatch(i->!i.complete()&&i.bytes()>0);
        code(()->restarted.purge(OP,new QuarantineService.PurgeRequest(held.digest(),r.id(),r.sha256(),true,true,"333333")),"QUARANTINE_CONFLICT");
        var done=restarted.purge(OP,partialBody);assertThat(done.state()).isEqualTo("purged");assertThat(restarted.purge(OP,partialBody)).isEqualTo(done);
        Files.createDirectory(payload());Files.writeString(payload().resolve("new-canary"),"must survive");code(()->restarted.purge(OP,partialBody),"QUARANTINE_CONFLICT");assertThat(Files.readString(payload().resolve("new-canary"))).isEqualTo("must survive");
    }
    @Test void httpEndpointsStreamZipAndRetainLocalGuardsAndSafeDtos()throws Exception {
        var http=http();String base="/api/storage/quarantine/"+OP;String requestId=UUID.randomUUID().toString();var body=new QuarantineService.FileBackupRequest(requestId,held.digest(),true);
        for(String endpoint:List.of(base+"/file-backups",base+"/purge")){http.perform(post(endpoint).contentType("application/json").content("{}")).andExpect(status().isForbidden());http.perform(post(endpoint).header("X-Local-Resume","1").header("Origin","https://attacker.invalid").contentType("application/json").content("{}")).andExpect(status().isForbidden());}
        http.perform(post(base+"/file-backups").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(body))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(requestId)).andExpect(jsonPath("$.downloaded").value(false)).andExpect(jsonPath("$.path").doesNotExist());
        var r=service.fileBackupStatus(OP,requestId);
        var async=http.perform(get(base+"/file-backups/"+requestId+"/download")).andExpect(request().asyncStarted()).andReturn();
        var response=http.perform(asyncDispatch(async)).andExpect(status().isOk()).andExpect(content().contentType("application/zip")).andExpect(header().string("Content-Length",Long.toString(r.bytes()))).andExpect(header().string("Content-Disposition","attachment; filename=\"quarantine-files-"+requestId+".zip\"")).andReturn().getResponse();
        assertThat(ImageService.sha(response.getContentAsByteArray())).isEqualTo(r.sha256());
        http.perform(get(base+"/file-backups/"+requestId)).andExpect(status().isOk()).andExpect(jsonPath("$.downloaded").value(true));
        http.perform(post(base+"/purge").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(purgeRequest(r)))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("purged")).andExpect(jsonPath("$.cleanup.exportId").value(requestId));
    }
    @Test void streamThreadHoldsReadLeaseUntilFlushAndExclusivePurgeWaits()throws Exception {
        var r=ticket();deliver(r);var download=service.download(OP,r.id());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var flushed=new AtomicBoolean();var workers=Executors.newFixedThreadPool(2);var purgeThread=new AtomicReference<Thread>();
        try{
            var streaming=workers.submit(()->{download.body().writeTo(new ByteArrayOutputStream(){boolean first=true;public void write(byte[] b,int off,int len){if(first){first=false;entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("stream release timed out");}catch(InterruptedException e){throw new RuntimeException(e);}}super.write(b,off,len);}public void flush(){flushed.set(true);}});return true;});
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var purging=workers.submit(()->{purgeThread.set(Thread.currentThread());var result=service.purge(OP,purgeRequest(r));assertThat(flushed).isTrue();return result;});
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->purgeThread.get()!=null&&purgeThread.get().getState()==Thread.State.TIMED_WAITING);
            assertThat(purging.isDone()).isFalse();assertThat(store.existing(OP).state()).isEqualTo("quarantined");release.countDown();assertThat(streaming.get(15,TimeUnit.SECONDS)).isTrue();assertThat(purging.get(20,TimeUnit.SECONDS).state()).isEqualTo("purged");
        }finally{release.countDown();workers.shutdownNow();}
    }
    @Test void freshReferencesAreIndependentRepeatableReadEvenInsideReadCommittedTransaction()throws Exception {
        var isolation=new AtomicReference<String>();var observed=new StoragePreviewService(jdbc,mapper,gate,transactions,data.toString(),store,clock){@Override StorageInspector.References references(){isolation.set(jdbc.queryForObject("SHOW transaction_isolation",String.class));return super.references();}};
        var outer=new TransactionTemplate(transactions);outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.execute(status->{jdbc.update("INSERT INTO resumes(id,title,document) VALUES (?, 'uncommitted-private', '{}'::jsonb)",UUID.randomUUID());assertThat(observed.freshReferences().resumes()).isEmpty();assertThat(isolation).hasValue("repeatable read");status.setRollbackOnly();return null;});
        assertThat(observed.freshReferences().consistent()).isTrue();
    }
    @Test void referenceQueriesShareOneDatabaseSnapshotAcrossConcurrentCommit()throws Exception {
        var firstQuery=new CountDownLatch(1);var committed=new CountDownLatch(1);var workers=Executors.newSingleThreadExecutor();
        var boundaryJdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())){@Override public <T> List<T> query(String sql,RowMapper<T> rowMapper){var rows=super.query(sql,rowMapper);if(sql.startsWith("SELECT id,CASE")&&sql.contains(" FROM resumes ")){firstQuery.countDown();try{if(!committed.await(5,TimeUnit.SECONDS))throw new AssertionError("reference commit timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}return rows;}};
        var observed=new StoragePreviewService(boundaryJdbc,mapper,gate,transactions,data.toString(),store,clock);
        try{var reading=workers.submit(observed::freshReferences);assertThat(firstQuery.await(5,TimeUnit.SECONDS)).isTrue();resume(RESUME,ResumeDocument.sample("one"));jdbc.update("INSERT INTO resume_versions(id,resume_id,title,label,document,source_revision) VALUES (?,?, 'test','test',?::jsonb,1)",UUID.fromString(VERSION),UUID.fromString(RESUME),mapper.writeValueAsString(ResumeDocument.sample("one")));committed.countDown();var snapshot=reading.get(5,TimeUnit.SECONDS);assertThat(snapshot.resumes()).isEmpty();assertThat(snapshot.versions()).isEmpty();assertThat(preview.freshReferences().resumes()).contains(RESUME);assertThat(preview.freshReferences().versions()).containsKey(VERSION);}finally{committed.countDown();workers.shutdownNow();}
    }
    @Test void zipRouteUsesTenMinuteAsyncTimeoutForBoundedLargeTransfers()throws Exception {
        var r=ticket();var async=http().perform(get("/api/storage/quarantine/"+OP+"/file-backups/"+r.id()+"/download")).andExpect(request().asyncStarted()).andReturn();
        assertThat(async.getRequest().getAsyncContext().getTimeout()).isEqualTo(600000L);
        async.getAsyncResult(15000);
    }
    @Test void cancelledRequestBeforeFirstByteOrDuringFlushNeverAuthorizesPurge()throws Exception {
        var r=ticket();var cancelled=new AtomicBoolean(true);var output=new ByteArrayOutputStream();
        assertThatThrownBy(()->service.download(OP,r.id(),cancelled::get).body().writeTo(output)).isInstanceOf(IOException.class);assertThat(output.size()).isZero();
        cancelled.set(false);
        assertThatThrownBy(()->service.download(OP,r.id(),cancelled::get).body().writeTo(new ByteArrayOutputStream(){public void flush(){cancelled.set(true);}})).isInstanceOf(IOException.class);
        assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isFalse();intact();
    }
    @Test void thirtyTwoTicketsAreBoundedAndOnlyPostReclaimsExpiredAuthorization()throws Exception {
        var first=ticket();for(int i=1;i<32;i++)ticket();
        code(this::ticket,"QUARANTINE_ARCHIVE_LIMIT");assertThat(service.fileBackupStatus(OP,first.id())).isEqualTo(first);
        clock.instant=now.plusSeconds(600);assertThat(service.fileBackupStatus(OP,first.id()).downloaded()).isFalse();
        var next=ticket();assertThat(next.id()).isNotEqualTo(first.id());code(()->service.fileBackupStatus(OP,first.id()),"QUARANTINE_BACKUP_NOT_FOUND");intact();
    }
    @Test void successfulFlushRenewsBothTicketAndArtifactFromDeliveryTime()throws Exception {
        var r=ticket();clock.instant=now.plusSeconds(599);service.download(OP,r.id()).body().writeTo(new ByteArrayOutputStream(){public void flush(){clock.instant=now.plusSeconds(600);}});
        var delivered=service.fileBackupStatus(OP,r.id());assertThat(delivered.downloaded()).isTrue();assertThat(delivered.expiresAt()).isEqualTo(now.plusSeconds(1200));
        clock.instant=now.plusSeconds(1199);assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isTrue();
        clock.instant=now.plusSeconds(1200);assertThat(service.fileBackupStatus(OP,r.id()).downloaded()).isFalse();code(()->service.purge(OP,purgeRequest(r)),"QUARANTINE_BACKUP_EXPIRED");intact();
    }
    @Test void exclusivePurgeSerializesNormalMutationAcrossItsFreshReferenceCallback()throws Exception {
        var checking=new CountDownLatch(1);var release=new CountDownLatch(1);var mutated=new AtomicBoolean();var mutationThread=new AtomicReference<Thread>();
        var blocked=new StoragePreviewService(jdbc,mapper,gate,transactions,data.toString(),store,clock){@Override StorageInspector.References freshReferences(){checking.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("reference release timeout");}catch(InterruptedException e){throw new RuntimeException(e);}assertThat(mutated).isFalse();return super.freshReferences();}};
        var guarded=service(store,blocked,archive);var r=guarded.fileBackup(OP,new QuarantineService.FileBackupRequest(UUID.randomUUID().toString(),held.digest(),true));guarded.download(OP,r.id()).body().writeTo(new ByteArrayOutputStream());
        var workers=Executors.newFixedThreadPool(2);
        try{
            var purging=workers.submit(()->guarded.purge(OP,purgeRequest(r)));assertThat(checking.await(10,TimeUnit.SECONDS)).isTrue();
            var update=workers.submit(()->{mutationThread.set(Thread.currentThread());try(var lease=gate.mutation()){assertThat(payload()).doesNotExist();assertThat(discard()).doesNotExist();resume(UUID.randomUUID().toString(),ResumeDocument.sample("one"));mutated.set(true);return true;}catch(ApiException e){assertThat(e.code).isEqualTo("WORKSPACE_BUSY");return false;}});
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->mutationThread.get()!=null&&mutationThread.get().getState()==Thread.State.TIMED_WAITING);
            assertThat(mutated).isFalse();assertThat(payload()).isDirectory();release.countDown();assertThat(purging.get(20,TimeUnit.SECONDS).state()).isEqualTo("purged");
            if(!update.get(5,TimeUnit.SECONDS)){assertThat(mutated).isFalse();try(var lease=gate.mutation()){resume(UUID.randomUUID().toString(),ResumeDocument.sample("one"));mutated.set(true);}}
            assertThat(mutated).isTrue();
        }finally{release.countDown();workers.shutdownNow();}
    }
    @Test void durableRetryIgnoresAnUnrelatedRestartCacheTicketWithReusedExportId()throws Exception {
        var calls=new AtomicInteger();var failing=new QuarantineStore(data,mapper,clock,(a,b)->Files.move(a,b),new QuarantineJournalIo(),p->{if(calls.incrementAndGet()==2)throw new IOException("partial cleanup");Files.delete(p);});
        var first=service(failing,preview,archive);var r=first.fileBackup(OP,new QuarantineService.FileBackupRequest(UUID.randomUUID().toString(),held.digest(),true));first.download(OP,r.id()).body().writeTo(new ByteArrayOutputStream());var request=purgeRequest(r);assertThat(first.purge(OP,request).state()).isEqualTo("purging");
        var freshStore=new QuarantineStore(data,mapper,clock);var freshArchive=new QuarantineArchive(data,cache,mapper,clock);var restarted=service(freshStore,preview,freshArchive);
        String otherId=UUID.randomUUID().toString(),otherOp=UUID.randomUUID().toString();byte[] pdf="%PDF-new-independent-canary".getBytes();byte[] metadata=mapper.writeValueAsBytes(new ExportService.Export(otherId,BACKUP,"a".repeat(64),ImageService.sha(pdf),old,null,null,null));var entries=new ArrayList<QuarantineFiles.Entry>();
        for(var bytes:List.of(Map.entry("exports/"+otherId+".json",metadata),Map.entry("exports/"+otherId+".pdf",pdf))){Path path=data.resolve(bytes.getKey());Files.write(path,bytes.getValue());Files.setLastModifiedTime(path,FileTime.from(old));entries.add(new QuarantineFiles.Entry(bytes.getKey(),bytes.getValue().length,ImageService.sha(bytes.getValue()),old));}
        var otherPlan=new QuarantineFiles.Plan(otherOp,"d".repeat(64),"e".repeat(64),now,List.of(new QuarantineFiles.Target("pdf",otherId,pdf.length+metadata.length,entries)));freshStore.reserve(otherPlan);var other=freshStore.moveToQuarantine(otherOp,BACKUP);
        var unrelated=restarted.fileBackup(otherOp,new QuarantineService.FileBackupRequest(r.id(),other.digest(),true));var unrelatedArtifact=freshArchive.create(otherPlan,other.digest());
        assertThatCode(()->assertThat(restarted.purge(OP,request).state()).isEqualTo("purged")).doesNotThrowAnyException();assertThat(restarted.purge(OP,request).state()).isEqualTo("purged");
        assertThat(Files.readAllBytes(data.resolve("quarantine/"+otherOp+"/payload/exports/"+otherId+".pdf"))).isEqualTo(pdf);assertThat(unrelatedArtifact.path()).isRegularFile();freshArchive.verify(unrelatedArtifact);assertThat(restarted.fileBackupStatus(otherOp,r.id())).isEqualTo(unrelated);
    }
    static final class MutableClock extends Clock {volatile Instant instant;MutableClock(Instant i){instant=i;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return instant;}}
}
