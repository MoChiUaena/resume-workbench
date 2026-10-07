package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.datasource.hikari.schema=backup_test","spring.flyway.schemas=backup_test","spring.flyway.default-schema=backup_test","resume.data-dir=./target/backup-test-files","resume.max-backup-bytes=52428800"})
class BackupServiceTest {
    @Autowired BackupService backups;
    @Autowired ResumeService resumes;
    @Autowired ImageService images;
    @Autowired AttachmentStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired Validator validator;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WorkspaceGate gate;
    final Path data=Path.of("target/backup-test-files").toAbsolutePath();
    @BeforeEach void prepare()throws Exception{
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("backup_test");
        jdbc.execute("TRUNCATE docx_imports,job_reports,version_assets,resume_assets,resume_versions,resumes,attachments");
        BackupArchive.removeTree(data);
    }
    private ResumeDocument withImages()throws Exception{
        var photo=images.importImage(Files.readAllBytes(Path.of("src/main/resources/static/samples/nailong-portrait.png")));
        var logo=images.importImage(Files.readAllBytes(Path.of("fixtures/university-logo.png")));
        var doc=ResumeDocument.sample("one");var l=doc.layout();
        return new ResumeDocument(2,doc.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,
            new ResumeDraft.ImageSlot(photo.id(),true,26,34,"cover",0,1,50,50),new ResumeDraft.ImageSlot(logo.id(),true,26,26,"contain",0,1,50,50)));
    }
    @Test void historyIncludesManualAutomaticAndLegacyArchivesAndRestoresWithoutChangingCurrentRows()throws Exception {
        var original=resumes.create("历史索引 · 合成简历",withImages());
        var manual=backups.create();var automatic=backups.automatic(null);assertThat(automatic.outcome()).isEqualTo("created");
        var skipped=backups.automatic(automatic.fingerprint());assertThat(skipped.outcome()).isEqualTo("unchanged");
        assertThat(backups.history(0).items()).extracting(BackupCatalog.Item::kind).containsExactlyInAnyOrder("manual","automatic");
        assertThat(backups.history(0).items()).hasSize(2);assertThat(resumes.get(original.id())).isEqualTo(original);
        Files.delete(data.resolve("backups/"+manual.id()+".json"));
        assertThat(backups.history(0).items()).anyMatch(item->item.kind().equals("legacy")&&item.backup().id().equals(manual.id()));
        var restored=backups.restoreSaved(automatic.backup().id());assertThat(restored.resumes()).isEqualTo(1);assertThat(resumes.get(original.id())).isEqualTo(original);
        assertThat(resumes.get(restored.resumeIds().getFirst()).document().content()).isEqualTo(original.document().content());
        assertThat(backups.automatic(automatic.fingerprint()).outcome()).isEqualTo("created");
    }
    @Test void damagedCatalogOrArchiveIsReportedAndPreservesOtherBackups()throws Exception {
        resumes.create("校验 · 合成简历",withImages());var good=backups.create();var damaged=backups.create();
        Path zip=backups.download(damaged.id());byte[] bytes=Files.readAllBytes(zip);bytes[bytes.length/2]^=1;Files.write(zip,bytes);
        assertThatThrownBy(()->backups.download(damaged.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_CORRUPT"));
        Files.writeString(data.resolve("backups/"+damaged.id()+".json"),"broken");assertThat(backups.history(0).unreadable()).isEqualTo(1);
        assertThat(backups.download(good.id())).isRegularFile();assertThat(Files.exists(zip)).isTrue();
        assertThatThrownBy(()->backups.restoreSaved("../"+good.id())).isInstanceOf(ApiException.class);assertThat(resumes.list()).hasSize(1);
    }
    @Test void completeBackupRestoresEditableDataVersionsOriginalsAndExportsWithoutOverwrite()throws Exception{
        var base=withImages();var layout=base.layout();
        var style=new ResumeDocument.Presentation("en","#c65c19","center","icons","bar",18,22,20,4,1.2);
        var doc=new ResumeDocument(3,base.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),layout.swapImages(),layout.photo(),layout.logo(),style));
        var original=resumes.create("基础简历",doc);
        var checkpoint=resumes.checkpoint(original.id(),1,"首次投递");
        var l=doc.layout();var withoutPhoto=new ResumeDocument(3,doc.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,new ResumeDraft.ImageSlot(null,true,26,34,"cover",0,1,50,50),l.logo(),l.presentation()));
        resumes.save(original.id(),new ResumeService.Save("当前版",withoutPhoto,1,UUID.randomUUID()));
        Files.createDirectories(data.resolve("exports"));String exportId=UUID.randomUUID().toString();byte[] pdf="%PDF-1.4\nfixture\n%%EOF".getBytes();
        Files.write(data.resolve("exports/"+exportId+".pdf"),pdf);mapper.writeValue(data.resolve("exports/"+exportId+".json").toFile(),new ExportService.Export(exportId,UUID.randomUUID().toString(),"0".repeat(64),ImageService.sha(pdf),Instant.now(),original.id().toString(),checkpoint.id().toString(),1L));
        var created=backups.create();assertThat(created.attachments()).isEqualTo(2);assertThat(created.exports()).isEqualTo(1);
        byte[] bytes=Files.readAllBytes(backups.download(created.id()));
        assertThat(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(".env","RESUME_DB_PASSWORD");
        var restored=backups.restore(new ByteArrayInputStream(bytes));assertThat(restored.resumes()).isEqualTo(1);assertThat(restored.versions()).isEqualTo(2);assertThat(restored.exports()).isEqualTo(1);
        assertThat(resumes.list()).hasSize(2);assertThat(resumes.get(original.id()).title()).isEqualTo("当前版");
        UUID importedId=restored.resumeIds().getFirst();var imported=resumes.get(importedId);
        assertThat(importedId).isNotEqualTo(original.id());assertThat(imported.document().content()).isEqualTo(doc.content());assertThat(imported.document().layout().photo().id()).isNull();
        assertThat(imported.document().layout().presentation()).isEqualTo(style);
        var importedVersion=resumes.versions(importedId).stream().filter(v->v.label().equals("首次投递")).findFirst().orElseThrow();
        var recovered=resumes.restore(importedId,importedVersion.id(),imported.revision());String photoId=recovered.document().layout().photo().id();
        assertThat(photoId).isNotEqualTo(doc.layout().photo().id());assertThat(storage.image(photoId)).isEqualTo(storage.image(doc.layout().photo().id()));assertThat(storage.original(photoId)).isEqualTo(storage.original(doc.layout().photo().id()));
        assertThat(recovered.document().layout().presentation()).isEqualTo(style);
    }
    @ParameterizedTest @ValueSource(ints={2,3}) void olderBackupsRestoreWithTheirOriginalAppearance(int sourceSchema)throws Exception {
        var doc=withImages();resumes.create("旧版本简历",doc);
        var created=backups.create();var files=new LinkedHashMap<String,byte[]>();
        try(var zip=new java.util.zip.ZipInputStream(Files.newInputStream(backups.download(created.id())))){
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(!entry.getName().equals("manifest.json"))files.put(entry.getName(),zip.readAllBytes());
        }
        var workspace=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(files.get("workspace.json"));workspace.put("schemaVersion",sourceSchema);
        for(String group:List.of("resumes","versions"))for(var record:workspace.path(group)){
            var document=(com.fasterxml.jackson.databind.node.ObjectNode)record.path("document");document.put("schemaVersion",sourceSchema);
            if(sourceSchema==2)((com.fasterxml.jackson.databind.node.ObjectNode)document.path("layout")).remove("presentation");
        }
        files.put("workspace.json",mapper.writeValueAsBytes(workspace));files.put("settings.json",mapper.writeValueAsBytes(new BackupData.Settings(sourceSchema,5242880,24000000)));
        var entries=files.entrySet().stream().map(e->new BackupArchive.FileEntry(e.getKey(),e.getValue().length,ImageService.sha(e.getValue()))).toList();
        files.put("manifest.json",mapper.writeValueAsBytes(new BackupArchive.Manifest(BackupArchive.FORMAT,1,sourceSchema,Instant.now(),entries)));
        var bytes=new ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)){
            for(var entry:files.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        var result=backups.restore(new ByteArrayInputStream(bytes.toByteArray()));var recovered=resumes.get(result.resumeIds().getFirst());
        assertThat(recovered.document().schemaVersion()).isEqualTo(4);
        assertThat(recovered.document().layout().presentation()).isEqualTo(ResumeDocument.Presentation.defaults(doc.layout().marginMm()));
    }
    @Test void webpBackupKeepsOriginalsTransparencyHistoryAndAutomaticFingerprint()throws Exception {
        var photo=images.importImage(Files.readAllBytes(Path.of("fixtures/portrait-exif-6.webp")));
        var logo=images.importImage(Files.readAllBytes(Path.of("fixtures/university-logo-lossless.webp")));
        var sample=ResumeDocument.sample("two");var l=sample.layout();
        var doc=new ResumeDocument(4,sample.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,
            new ResumeDraft.ImageSlot(photo.id(),true,26,34,"cover",1,1.15,45,55),new ResumeDraft.ImageSlot(logo.id(),true,26,26,"contain",0,1,50,50),l.presentation()));
        var original=resumes.create("WebP 完整备份",doc);resumes.checkpoint(original.id(),original.revision(),"WebP 基线");
        var automatic=backups.automatic(null);assertThat(automatic.outcome()).isEqualTo("created");
        assertThat(backups.automatic(automatic.fingerprint()).outcome()).isEqualTo("unchanged");
        var restored=backups.restoreSaved(automatic.backup().id());var recovered=resumes.get(restored.resumeIds().getFirst());
        assertThat(resumes.get(original.id())).isEqualTo(original);assertThat(resumes.list()).hasSize(2);
        assertThat(recovered.document().content()).isEqualTo(doc.content());
        assertThat(recovered.document().layout().photo().quarterTurns()).isEqualTo(1);
        assertThat(resumes.versions(recovered.id())).anyMatch(v->v.label().equals("WebP 基线"));
        for(var pair:List.of(Map.entry(photo.id(),recovered.document().layout().photo().id()),Map.entry(logo.id(),recovered.document().layout().logo().id()))) {
            assertThat(pair.getValue()).isNotEqualTo(pair.getKey());
            assertThat(storage.metadata(pair.getValue()).format()).isEqualTo("WEBP");
            assertThat(storage.original(pair.getValue())).isEqualTo(storage.original(pair.getKey()));
            assertThat(storage.image(pair.getValue())).isEqualTo(storage.image(pair.getKey()));
        }
        assertThat(storage.metadata(recovered.document().layout().photo().id()).exifOrientation()).isEqualTo(6);
    }
    @Test void aRehashedAnimatedWebpArchiveCannotBypassTheStaticImageRule()throws Exception {
        var photo=images.importImage(Files.readAllBytes(Path.of("fixtures/portrait-lossy.webp")));
        var sample=ResumeDocument.sample("one");var l=sample.layout();
        var doc=new ResumeDocument(4,sample.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,
            new ResumeDraft.ImageSlot(photo.id(),true,26,34,"cover",0,1,50,50),new ResumeDraft.ImageSlot(null,false,26,26,"contain",0,1,50,50),l.presentation()));
        var original=resumes.create("动画拒绝检查",doc);var created=backups.create();var files=new LinkedHashMap<String,byte[]>();
        try(var zip=new java.util.zip.ZipInputStream(Files.newInputStream(backups.download(created.id())))) {
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(!entry.getName().equals("manifest.json"))files.put(entry.getName(),zip.readAllBytes());
        }
        byte[] animated=Files.readAllBytes(Path.of("fixtures/animated.webp"));
        var workspace=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(files.get("workspace.json"));
        var asset=(com.fasterxml.jackson.databind.node.ObjectNode)workspace.path("attachments").get(0);
        asset.put("bytes",animated.length);asset.put("sourceWidth",16);asset.put("sourceHeight",16);asset.put("sha256",ImageService.sha(animated));
        files.put("workspace.json",mapper.writeValueAsBytes(workspace));files.put("attachments/"+photo.id()+"/metadata.json",mapper.writeValueAsBytes(asset));
        files.put("attachments/"+photo.id()+"/original.webp",animated);
        var entries=files.entrySet().stream().map(e->new BackupArchive.FileEntry(e.getKey(),e.getValue().length,ImageService.sha(e.getValue()))).toList();
        files.put("manifest.json",mapper.writeValueAsBytes(new BackupArchive.Manifest(BackupArchive.FORMAT,1,4,Instant.now(),entries)));
        var bytes=new ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            for(var entry:files.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}
        }
        assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(bytes.toByteArray()))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_INVALID"));
        assertThat(resumes.get(original.id())).isEqualTo(original);assertThat(resumes.list()).hasSize(1);
        assertThat(storage.original(photo.id())).isEqualTo(Files.readAllBytes(Path.of("fixtures/portrait-lossy.webp")));
    }
    @Test void corruptionFailsBeforeImportAndPreservesExistingRows()throws Exception{
        resumes.create("唯一简历",withImages());var created=backups.create();byte[] bytes=Files.readAllBytes(backups.download(created.id()));bytes[bytes.length/2]^=1;
        assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(bytes))).isInstanceOf(ApiException.class);
        assertThat(resumes.list()).hasSize(1);
    }
    @Test void filesystemFailureRollsBackImportedRowsAndCleansOnlyNewFiles()throws Exception{
        resumes.create("原始简历",withImages());var created=backups.create();byte[] bytes=Files.readAllBytes(backups.download(created.id()));long before;try(var files=Files.list(data.resolve("attachments"))){before=files.count();}
        var counter=new AtomicInteger();AttachmentStorage faulty=new AttachmentStorage(){
            public void save(ImageService.Asset a,byte[] original,byte[] image)throws IOException{if(counter.incrementAndGet()==2)throw new IOException("simulated full disk");storage.save(a,original,image);}
            public ImageService.Asset metadata(String id)throws IOException{return storage.metadata(id);}
            public byte[] image(String id)throws IOException{return storage.image(id);}
            public byte[] original(String id)throws IOException{return storage.original(id);}
            public void delete(String id)throws IOException{storage.delete(id);}
        };
        var service=new BackupService(jdbc,mapper,validator,faulty,gate,transactions,data.toString(),52428800,5242880,24000000);
        assertThatThrownBy(()->service.restore(new ByteArrayInputStream(bytes))).isInstanceOf(ApiException.class);
        assertThat(resumes.list()).hasSize(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments",Integer.class)).isEqualTo(2);
        try(var files=Files.list(data.resolve("attachments"))){assertThat(files.count()).isEqualTo(before);}
    }
    @Test void exclusiveLeaseActuallyPausesMutationThreads()throws Exception{
        var entered=new CountDownLatch(1);ExecutorService executor=Executors.newSingleThreadExecutor();Future<?> worker;
        try(var exclusive=gate.exclusive()){
            worker=executor.submit(()->{try(var mutation=gate.mutation()){entered.countDown();}});
            assertThat(entered.await(80,TimeUnit.MILLISECONDS)).isFalse();
        }
        worker.get(2,TimeUnit.SECONDS);assertThat(entered.getCount()).isZero();executor.shutdownNow();
    }
}
