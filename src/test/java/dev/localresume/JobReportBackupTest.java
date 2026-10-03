package dev.localresume;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.hikari.schema=job_report_backup_test","spring.flyway.schemas=job_report_backup_test","spring.flyway.default-schema=job_report_backup_test","resume.data-dir=./target/qa-data/job-report-backup"})
class JobReportBackupTest {
    @Autowired JobMatches matches;
    @Autowired JobReportHistory history;
    @Autowired BackupService backups;
    @Autowired ResumeService resumes;
    @Autowired ModelSettings settings;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ModelGateway gateway;
    ResumeService.Resume resume;
    JobMatches.Preview preview;
    JobMatches.Report report;
    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("job_report_backup_test");
        jdbc.update("DELETE FROM resumes");jdbc.update("DELETE FROM attachments");
        var base=ResumeDocument.sample("one");var section=new ResumeDocument.Section("backup-report-section","project","Java 项目",true,false,List.of(new ResumeDocument.Entry("backup-report-entry","","",true,List.of("Java archive sample"))));
        resume=resumes.create("含报告的简历",new ResumeDocument(4,new ResumeDocument.Content("奶龙","","","","",List.of(section)),base.layout()));
        var config=settings.view();if(config.profiles().isEmpty())config=settings.save(null,new ModelSettings.Edit(config.revision(),"备份测试模型","compatible","http://127.0.0.1:9999/v1","qa-report-model","BACKUP-REPORT-KEY-CANARY",false));
        if(!config.enabled())config=settings.enable(new ModelSettings.Enable(config.revision(),true));
        when(gateway.matchJob(any(),anyString(),anyString())).thenReturn("{\"items\":[{\"requirement\":\"Java\",\"status\":\"supported\",\"evidence\":[{\"sourceId\":\"s1\",\"quote\":\"Java\"}],\"advice\":\"\"}],\"suggestions\":[]}");
        preview=matches.preview(new JobMatches.Request(resume.id(),1,List.of(section.id()),"需要 Java",config.defaultId(),config.revision()));report=matches.generate(new JobMatches.Generate(preview.id(),true));
    }
    JobReportHistory.Detail save(){return history.save(resume.id(),new JobReportHistory.Save(preview.id(),report.id(),"Java 投递分析"));}
    Map<String,byte[]> entries(byte[] bytes)throws IOException{var files=new LinkedHashMap<String,byte[]>();try(var in=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=in.getNextEntry())!=null)files.put(entry.getName(),in.readAllBytes());}return files;}
    byte[] archive(Map<String,byte[]> files)throws Exception{
        files.remove("manifest.json");var records=new ArrayList<BackupArchive.FileEntry>();for(var entry:files.entrySet())records.add(new BackupArchive.FileEntry(entry.getKey(),entry.getValue().length,ImageService.sha(entry.getValue())));
        int schema=mapper.readTree(files.get("workspace.json")).path("schemaVersion").asInt();
        var result=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(result)){for(var entry:files.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(mapper.writeValueAsBytes(new BackupArchive.Manifest(BackupArchive.FORMAT,1,schema,java.time.Instant.now(),List.copyOf(records))));zip.closeEntry();}return result.toByteArray();
    }
    void replacePayload(Map<String,byte[]> files,UUID id,byte[] payload)throws Exception {
        files.put("job-reports/"+id+".json",payload);
        var workspace=(ObjectNode)mapper.readTree(files.get("workspace.json"));
        ((ObjectNode)workspace.path("jobReports").get(0)).put("sha256",ImageService.sha(payload));
        files.put("workspace.json",mapper.writeValueAsBytes(workspace));
    }
    ObjectNode nearLimitSnapshot(JobReportSnapshot snapshot)throws Exception {
        var payload=(ObjectNode)mapper.valueToTree(snapshot);var sources=new ArrayList<JobMatches.Source>();
        for(int n=1;n<=60;n++)sources.add(new JobMatches.Source("s"+n,"节".repeat(48)+String.format(Locale.ROOT,"%02d",n),
            "项".repeat(50),0,"章".repeat(120),"目".repeat(160),"Java"+"文".repeat(196)));
        payload.set("sources",mapper.valueToTree(sources));payload.put("jobDescription","Java");
        payload.put("destination","http://127.0.0.1:9999/v1/"+"a".repeat(1200));
        ((ObjectNode)payload.path("items").get(0)).put("advice","析".repeat(800));
        int padding=JobReportSnapshot.MAX_BYTES-8-mapper.writeValueAsBytes(payload).length;
        assertThat(padding).isPositive();payload.put("jobDescription","Java"+"岗".repeat(padding/3)+"x".repeat(padding%3));
        assertThat(payload.path("jobDescription").asText().length()).isLessThanOrEqualTo(6000);
        assertThat(mapper.writeValueAsBytes(payload)).hasSize(JobReportSnapshot.MAX_BYTES-8);
        return payload;
    }
    @Test void fullBackupRestoresReadOnlyReportsUnderRemappedResumeAndReportIds()throws Exception{
        var saved=save();var backup=backups.create();var files=entries(Files.readAllBytes(backups.download(backup.id())));
        var workspace=mapper.readTree(files.get("workspace.json"));assertThat(workspace.path("schemaVersion").asInt()).isEqualTo(5);
        assertThat(workspace.toString()).doesNotContain("previewId","preview_id",preview.id().toString());
        assertThat(workspace.path("jobReports").size()).isEqualTo(1);String reportPath="job-reports/"+saved.id()+".json";
        assertThat(files).containsKey(reportPath);assertThat(new String(files.get(reportPath),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("BACKUP-REPORT-KEY-CANARY","encryptedKey","expiresAt","previewId","preview_id",preview.id().toString());
        var restored=backups.restore(new ByteArrayInputStream(Files.readAllBytes(backups.download(backup.id()))));
        var newResume=restored.resumeIds().getFirst();var list=history.history(newResume,0);assertThat(list.total()).isEqualTo(1);
        var detail=history.get(newResume,list.items().getFirst().id());assertThat(detail.id()).isNotEqualTo(saved.id());assertThat(detail.snapshot()).isEqualTo(saved.snapshot());
        UUID internalPreview=jdbc.queryForObject("SELECT preview_id FROM job_reports WHERE id=?",UUID.class,detail.id());
        assertThat(internalPreview).isNotEqualTo(preview.id()).isNotEqualTo(saved.id()).isNotEqualTo(detail.id());
        assertThatThrownBy(()->history.save(newResume,new JobReportHistory.Save(preview.id(),detail.id(),detail.label())))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("JOB_REPORT_SOURCE_INVALID"));
        assertThat(detail.resumeId()).isEqualTo(newResume);assertThat(resumes.get(resume.id())).isEqualTo(resume);verify(gateway,times(1)).matchJob(any(),anyString(),anyString());
    }
    @Test void savedAndDeletedReportsParticipateInAutomaticBackupFingerprint() {
        var baseline=backups.automatic(null);save();var changed=backups.automatic(baseline.fingerprint());assertThat(changed.outcome()).isEqualTo("created");
        assertThat(backups.automatic(changed.fingerprint()).outcome()).isEqualTo("unchanged");history.delete(resume.id(),report.id());assertThat(backups.automatic(changed.fingerprint()).outcome()).isEqualTo("created");
    }
    @Test void invalidArchivedReferencesAreRejectedBeforeAnyRestoreMutation()throws Exception {
        var saved=save();var backup=backups.create();var files=entries(Files.readAllBytes(backups.download(backup.id())));String path="job-reports/"+saved.id()+".json";
        var payload=(ObjectNode)mapper.readTree(files.get(path));((ObjectNode)payload.path("items").get(0).path("evidence").get(0)).put("quote","untrusted fabricated quotation");files.put(path,mapper.writeValueAsBytes(payload));
        var workspace=(ObjectNode)mapper.readTree(files.get("workspace.json"));((ObjectNode)workspace.path("jobReports").get(0)).put("sha256",ImageService.sha(files.get(path)));files.put("workspace.json",mapper.writeValueAsBytes(workspace));
        assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(archive(files)))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_INVALID"));
        assertThat(resumes.list()).hasSize(1);assertThat(history.history(resume.id(),0).total()).isEqualTo(1);
    }
    @Test void oldWorkspaceBackupsWithoutReportsRemainRestorable()throws Exception {
        var backup=backups.create();var files=entries(Files.readAllBytes(backups.download(backup.id())));var workspace=(ObjectNode)mapper.readTree(files.get("workspace.json"));workspace.put("schemaVersion",4);workspace.remove("jobReports");files.put("workspace.json",mapper.writeValueAsBytes(workspace));
        var legacySettings=(ObjectNode)mapper.readTree(files.get("settings.json"));legacySettings.put("schemaVersion",4);files.put("settings.json",mapper.writeValueAsBytes(legacySettings));
        var restored=backups.restore(new ByteArrayInputStream(archive(files)));assertThat(history.history(restored.resumeIds().getFirst(),0).total()).isZero();
    }
    @Test void reportInventoryAndOwnershipCannotBeChangedByRehashingTheArchive()throws Exception {
        var saved=save();var backup=backups.create();byte[] original=Files.readAllBytes(backups.download(backup.id()));
        for(String mode:List.of("missingList","duplicateId","foreignParent","futureRevision","legacyDowngrade","extraFile")) {
            var files=entries(original);var workspace=(ObjectNode)mapper.readTree(files.get("workspace.json"));
            var metadata=(ObjectNode)workspace.path("jobReports").get(0);
            switch(mode) {
                case "missingList"->workspace.remove("jobReports");
                case "duplicateId"->((com.fasterxml.jackson.databind.node.ArrayNode)workspace.path("jobReports")).add(metadata.deepCopy());
                case "foreignParent"->metadata.put("resumeId",UUID.randomUUID().toString());
                case "futureRevision"->metadata.put("sourceRevision",2);
                case "legacyDowngrade"->{workspace.put("schemaVersion",4);var config=(ObjectNode)mapper.readTree(files.get("settings.json"));config.put("schemaVersion",4);files.put("settings.json",mapper.writeValueAsBytes(config));}
                case "extraFile"->files.put("job-reports/"+UUID.randomUUID()+".json",files.get("job-reports/"+saved.id()+".json"));
            }
            files.put("workspace.json",mapper.writeValueAsBytes(workspace));
            assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(archive(files)))).as(mode)
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_INVALID"));
            assertThat(resumes.list()).hasSize(1);assertThat(history.get(resume.id(),saved.id())).isEqualTo(saved);
        }
    }
    @Test void reportSnapshotsRejectUnknownCredentialsDuplicateFieldsAndTrailingJson()throws Exception {
        var saved=save();var backup=backups.create();byte[] original=Files.readAllBytes(backups.download(backup.id()));String path="job-reports/"+saved.id()+".json";
        for(String mode:List.of("unknownCredential","duplicateField","trailingJson")) {
            var files=entries(original);String json=new String(files.get(path),java.nio.charset.StandardCharsets.UTF_8);
            json=switch(mode) {
                case "unknownCredential"->json.substring(0,json.length()-1)+",\"apiKey\":\"untrusted-key\"}";
                case "duplicateField"->json.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1");
                default->json+" {}";
            };
            files.put(path,json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var workspace=(ObjectNode)mapper.readTree(files.get("workspace.json"));((ObjectNode)workspace.path("jobReports").get(0)).put("sha256",ImageService.sha(files.get(path)));files.put("workspace.json",mapper.writeValueAsBytes(workspace));
            assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(archive(files)))).as(mode)
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_INVALID"));
            assertThat(resumes.list()).hasSize(1);assertThat(history.get(resume.id(),saved.id())).isEqualTo(saved);
        }
    }
    @ParameterizedTest(name="malformed snapshot: {0}")
    @ValueSource(strings={"fractionalSchema","textualSchema","fractionalRevision","textualRevision","fractionalParagraph","textualParagraph",
        "nullParagraph","missingParagraph","numericProfile","booleanProfile","numericTitle","booleanAdvice","numericReplacement","missingJob","nullJob"})
    void malformedSnapshotTypesAreRejectedBeforeRestoreMutation(String mode)throws Exception {
        var saved=save();var backup=backups.create();var files=entries(Files.readAllBytes(backups.download(backup.id())));
        var payload=(ObjectNode)mapper.valueToTree(saved.snapshot());var source=(ObjectNode)payload.path("sources").get(0);
        switch(mode) {
            case "fractionalSchema"->payload.put("schemaVersion",1.9);
            case "textualSchema"->payload.put("schemaVersion","1");
            case "fractionalRevision"->payload.put("sourceRevision",1.9);
            case "textualRevision"->payload.put("sourceRevision","1");
            case "fractionalParagraph"->source.put("paragraph",0.9);
            case "textualParagraph"->source.put("paragraph","0");
            case "nullParagraph"->source.putNull("paragraph");
            case "missingParagraph"->source.remove("paragraph");
            case "numericProfile"->payload.put("profileName",123);
            case "booleanProfile"->payload.put("profileName",true);
            case "numericTitle"->source.put("sectionTitle",123);
            case "booleanAdvice"->((ObjectNode)payload.path("items").get(0)).put("advice",false);
            case "numericReplacement"->{var candidate=mapper.createObjectNode().put("sourceId","s1").put("replacement",123);((com.fasterxml.jackson.databind.node.ArrayNode)payload.path("suggestions")).add(candidate);}
            case "missingJob"->payload.remove("jobDescription");
            case "nullJob"->payload.putNull("jobDescription");
        }
        replacePayload(files,saved.id(),mapper.writeValueAsBytes(payload));
        assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(archive(files))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_INVALID"));
        assertThat(resumes.list()).hasSize(1);assertThat(history.get(resume.id(),saved.id())).isEqualTo(saved);
    }
    @Test void nearLimitPortableSnapshotSurvivesActualJsonbDetailAndSubsequentBackup()throws Exception {
        var saved=save();var backup=backups.create();var files=entries(Files.readAllBytes(backups.download(backup.id())));
        byte[] compact=mapper.writeValueAsBytes(nearLimitSnapshot(saved.snapshot()));replacePayload(files,saved.id(),compact);
        var restored=backups.restore(new ByteArrayInputStream(archive(files)));var parent=restored.resumeIds().getFirst();
        UUID id=history.history(parent,0).items().getFirst().id();
        int jsonbBytes=jdbc.queryForObject("SELECT octet_length(snapshot::text) FROM job_reports WHERE id=?",Integer.class,id);
        assertThat(jsonbBytes).isGreaterThan(JobReportSnapshot.MAX_BYTES).isLessThanOrEqualTo(262144);
        System.out.println("near-limit fixture: canonical="+compact.length+", PostgreSQL JSONB="+jsonbBytes);
        var detail=assertDoesNotThrow(()->history.get(parent,id));assertThat(mapper.writeValueAsBytes(detail.snapshot())).isEqualTo(compact);
        var followup=backups.create();var output=entries(Files.readAllBytes(backups.download(followup.id())));
        assertThat(output.get("job-reports/"+id+".json")).isEqualTo(compact);
        System.out.println("near-limit JSONB round trip: canonical="+compact.length+", jsonb="+jsonbBytes+", detail/backup=success");
    }
    @Test void rawArchiveSnapshotLimitStillRejectsWhitespaceBeyond128KiBBeforeRestore()throws Exception {
        var saved=save();var backup=backups.create();var files=entries(Files.readAllBytes(backups.download(backup.id())));
        byte[] compact=mapper.writeValueAsBytes(saved.snapshot());var padded=Arrays.copyOf(compact,JobReportSnapshot.MAX_BYTES+1);
        Arrays.fill(padded,compact.length,padded.length,(byte)' ');replacePayload(files,saved.id(),padded);
        assertThatThrownBy(()->backups.restore(new ByteArrayInputStream(archive(files))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_TOO_LARGE"));
        assertThat(resumes.list()).hasSize(1);assertThat(history.get(resume.id(),saved.id())).isEqualTo(saved);
    }
    @Test void databasePresentationCannotBypassTheCanonicalSnapshotLimit()throws Exception {
        var saved=save();var payload=nearLimitSnapshot(saved.snapshot());
        payload.put("jobDescription",payload.path("jobDescription").asText()+"xxxxxxxxx");
        byte[] compact=mapper.writeValueAsBytes(payload);assertThat(compact).hasSize(JobReportSnapshot.MAX_BYTES+1);
        jdbc.update("UPDATE job_reports SET snapshot=?::jsonb WHERE id=?",new String(compact,java.nio.charset.StandardCharsets.UTF_8),saved.id());
        assertThatThrownBy(()->history.get(resume.id(),saved.id()))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("JOB_REPORT_UNREADABLE"));
        assertThatThrownBy(()->backups.create())
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_INVALID"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_reports WHERE id=?",Integer.class,saved.id())).isEqualTo(1);
    }
    @Test void databasePresentationParserHasItsOwnRawBoundAndRetainsCanonicalValidation()throws Exception {
        var saved=save();byte[] compact=mapper.writeValueAsBytes(saved.snapshot());
        byte[] bounded=Arrays.copyOf(compact,JobReportSnapshot.MAX_DATABASE_BYTES);Arrays.fill(bounded,compact.length,bounded.length,(byte)' ');
        assertThat(JobReportSnapshot.parseDatabase(mapper,bounded)).isEqualTo(saved.snapshot());
        byte[] oversized=Arrays.copyOf(bounded,bounded.length+1);oversized[oversized.length-1]=' ';
        assertThatThrownBy(()->JobReportSnapshot.parseDatabase(mapper,oversized))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("JOB_REPORT_SNAPSHOT_INVALID"));
        assertThatThrownBy(()->JobReportSnapshot.parse(mapper,bounded))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("JOB_REPORT_SNAPSHOT_INVALID"));
    }
}
