package dev.localresume;

import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import javax.sql.DataSource;
import jakarta.validation.Validator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Run only in the disposable remote CI PostgreSQL schema; never against a user database. */
@SpringBootTest(properties={"spring.datasource.hikari.schema=resume_test","spring.flyway.schemas=resume_test","spring.flyway.default-schema=resume_test","resume.data-dir=./target/integration-data"})
@AutoConfigureMockMvc
@Transactional
class DocxImportsIntegrationTest {
    @Autowired DocxImports imports;
    @Autowired ResumeService resumes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired Validator validator;
    void isolated(){assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("resume_test");}
    @Test void migratedV3DocxReceiptKeepsItsOriginalTokenAndFingerprint()throws Exception {
        isolated();
        assertThat(jdbc.queryForObject("SELECT to_regclass('resume_test.docx_imports') IS NULL",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('resume_test.document_imports') IS NOT NULL",Boolean.class)).isTrue();
        var request=new DocxImportController.Create(UUID.randomUUID(),"旧版导入",ResumeDocument.sample("blank"));
        var original=resumes.create(request.title(),request.document());
        String payload=mapper.writeValueAsString(List.of(request.title(),mapper.readTree(mapper.writeValueAsString(request.document()))));
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO document_imports(mutation_id,request_sha256,resume_id) VALUES (?,?,?)",request.mutationId(),fingerprint,original.id());
        assertThat(imports.create(request).resume().id()).isEqualTo(original.id());
        assertThat(resumes.versions(original.id())).hasSize(1);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void v4MigrationPreservesAnActualV3ReceiptAndItsRetry()throws Exception {
        isolated();
        String schema="resume_test_v3_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE SCHEMA "+schema);
        try(var connection=dataSource.getConnection()) {
            connection.setSchema(schema);
            var isolatedSource=new SingleConnectionDataSource(connection,true);
            var isolatedJdbc=new JdbcTemplate(isolatedSource);
            var v3=Flyway.configure().dataSource(isolatedSource).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("3")).load();
            v3.migrate();
            assertThat(isolatedJdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(schema);
            var request=new DocxImportController.Create(UUID.randomUUID(),"V3 导入",ResumeDocument.sample("blank"));
            UUID resumeId=UUID.randomUUID();String document=mapper.writeValueAsString(request.document());
            isolatedJdbc.update("INSERT INTO resumes(id,title,document) VALUES (?,?,?::jsonb)",resumeId,request.title(),document);
            isolatedJdbc.update("INSERT INTO resume_versions(id,resume_id,title,label,document,source_revision) VALUES (?,?,?,?,?::jsonb,1)",
                UUID.randomUUID(),resumeId,request.title(),"初始版本",document);
            String payload=mapper.writeValueAsString(List.of(request.title(),mapper.readTree(document)));
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
            isolatedJdbc.update("INSERT INTO docx_imports(mutation_id,request_sha256,resume_id) VALUES (?,?,?)",request.mutationId(),fingerprint,resumeId);
            Flyway.configure().dataSource(isolatedSource).schemas(schema).defaultSchema(schema)
                .target(MigrationVersion.fromVersion("4")).load().migrate();
            assertThat(isolatedJdbc.queryForObject("SELECT to_regclass(?) IS NULL",Boolean.class,schema+".docx_imports")).isTrue();
            assertThat(isolatedJdbc.queryForObject("SELECT count(*) FROM document_imports WHERE mutation_id=?",Integer.class,request.mutationId())).isEqualTo(1);
            var legacyResumes=new ResumeService(isolatedJdbc,mapper,null);
            var receipts=new DocumentImportReceipts(isolatedJdbc,legacyResumes,mapper,validator);
            assertThat(receipts.create(request.mutationId(),request.title(),request.document(),DocumentImportReceipts.Format.DOCX).resume().id())
                .isEqualTo(resumeId);
            assertThat(isolatedJdbc.queryForObject("SELECT count(*) FROM resume_versions WHERE resume_id=?",Integer.class,resumeId)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
    @Test void previewWritesNothingAndControllerCreatesOneInitialVersion()throws Exception {
        isolated();int before=resumes.list().size();
        var body=mvc.perform(multipart("/api/imports/docx/preview").file(new MockMultipartFile("file","简历.docx","",DocxFixtures.document(DocxFixtures.paragraph("教育背景")+DocxFixtures.paragraph("大学内容")))).header("X-Local-Resume","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.format").value("docx")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(resumes.list()).hasSize(before);var preview=mapper.readValue(body,DocxImports.Preview.class);UUID token=UUID.randomUUID();
        var request=new DocxImportController.Create(token,preview.title(),preview.document());
        var response=mvc.perform(post("/api/imports/docx/create").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mutationId").value(token.toString())).andReturn().getResponse().getContentAsByteArray();
        var created=mapper.readValue(response,DocxImports.Created.class);
        assertThat(created.resume().revision()).isEqualTo(1);assertThat(created.resume().document()).isEqualTo(preview.document());assertThat(resumes.versions(created.resume().id())).hasSize(1);
        assertThat(imports.create(request).resume().id()).isEqualTo(created.resume().id());assertThat(resumes.list()).hasSize(before+1);
    }
    @Test void exactRetriesReadCurrentEditedResumeAndDifferentRequestsConflict() {
        isolated();var request=new DocxImportController.Create(UUID.randomUUID(),"原始导入",ResumeDocument.sample("blank"));var first=imports.create(request);
        var edited=resumes.save(first.resume().id(),new ResumeService.Save("已编辑",first.resume().document(),1,UUID.randomUUID()));
        assertThat(imports.create(request).resume()).isEqualTo(edited);
        assertThatThrownBy(()->imports.create(new DocxImportController.Create(request.mutationId(),"不同标题",request.document()))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_IMPORT_CONFLICT"));
        var c=request.document().content();var changed=new ResumeDocument(4,new ResumeDocument.Content("另一姓名",c.headline(),c.email(),c.phone(),c.location(),c.sections()),request.document().layout());
        assertThatThrownBy(()->imports.create(new DocxImportController.Create(request.mutationId(),request.title(),changed))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_IMPORT_CONFLICT"));
        assertThat(resumes.versions(first.resume().id())).hasSize(1);
    }
    @Test void deletingImportedResumeLeavesReceiptAndReturnsGoneWithoutRecreation() {
        isolated();var request=new DocxImportController.Create(UUID.randomUUID(),"将删除",ResumeDocument.sample("blank"));var first=imports.create(request);resumes.delete(first.resume().id(),1);int before=resumes.list().size();
        assertThat(jdbc.queryForObject("SELECT resume_id IS NULL FROM document_imports WHERE mutation_id=?",Boolean.class,request.mutationId())).isTrue();
        assertThatThrownBy(()->imports.create(request)).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code).isEqualTo("DOCX_IMPORT_DELETED");assertThat(e.status).isEqualTo(410);});
        assertThat(resumes.list()).hasSize(before);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousIdenticalCreatesProduceOneRecordAndOneVersion()throws Exception {
        isolated();var request=new DocxImportController.Create(UUID.randomUUID(),"并发导入",ResumeDocument.sample("blank"));var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);UUID createdId=null;
        try {
            Callable<DocxImports.Created> work=()->{start.await();return imports.create(request);};var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            var first=a.get(20,TimeUnit.SECONDS);createdId=first.resume().id();var second=b.get(20,TimeUnit.SECONDS);
            assertThat(second.resume().id()).isEqualTo(createdId);assertThat(resumes.versions(createdId)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_imports WHERE mutation_id=?",Integer.class,request.mutationId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();pool.awaitTermination(20,TimeUnit.SECONDS);
            var ids=jdbc.queryForList("SELECT resume_id FROM document_imports WHERE mutation_id=?",UUID.class,request.mutationId());
            jdbc.update("DELETE FROM document_imports WHERE mutation_id=?",request.mutationId());for(var id:ids)if(id!=null)jdbc.update("DELETE FROM resumes WHERE id=?",id);
        }
    }
}
