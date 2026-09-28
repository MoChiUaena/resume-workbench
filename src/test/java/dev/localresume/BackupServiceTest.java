package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
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
        jdbc.execute("TRUNCATE version_assets,resume_assets,resume_versions,resumes,attachments");
        BackupArchive.removeTree(data);
    }
    private ResumeDocument withImages()throws Exception{
        var photo=images.importImage(Files.readAllBytes(Path.of("src/main/resources/static/samples/nailong-portrait.png")));
        var logo=images.importImage(Files.readAllBytes(Path.of("fixtures/university-logo.png")));
        var doc=ResumeDocument.sample("one");var l=doc.layout();
        return new ResumeDocument(2,doc.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,
            new ResumeDraft.ImageSlot(photo.id(),true,26,34,"cover",0,1,50,50),new ResumeDraft.ImageSlot(logo.id(),true,26,26,"contain",0,1,50,50)));
    }
    @Test void completeBackupRestoresEditableDataVersionsOriginalsAndExportsWithoutOverwrite()throws Exception{
        var doc=withImages();var original=resumes.create("基础简历",doc);
        var checkpoint=resumes.checkpoint(original.id(),1,"首次投递");
        var l=doc.layout();var withoutPhoto=new ResumeDocument(2,doc.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,new ResumeDraft.ImageSlot(null,true,26,34,"cover",0,1,50,50),l.logo()));
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
        var importedVersion=resumes.versions(importedId).stream().filter(v->v.label().equals("首次投递")).findFirst().orElseThrow();
        var recovered=resumes.restore(importedId,importedVersion.id(),imported.revision());String photoId=recovered.document().layout().photo().id();
        assertThat(photoId).isNotEqualTo(doc.layout().photo().id());assertThat(storage.image(photoId)).isEqualTo(storage.image(doc.layout().photo().id()));assertThat(storage.original(photoId)).isEqualTo(storage.original(doc.layout().photo().id()));
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
