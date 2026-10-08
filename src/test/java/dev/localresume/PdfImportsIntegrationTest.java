package dev.localresume;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Run only in the disposable remote CI PostgreSQL schema; never against a user database. */
@SpringBootTest(properties={"spring.datasource.hikari.schema=resume_test","spring.flyway.schemas=resume_test","spring.flyway.default-schema=resume_test","resume.data-dir=./target/integration-data"})
@AutoConfigureMockMvc
@Transactional
class PdfImportsIntegrationTest {
    @Autowired PdfImports imports;
    @Autowired ResumeService resumes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired BackupService backups;
    private void isolated(){assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("resume_test");}
    private static byte[] fixture(String name)throws Exception{return Files.readAllBytes(Path.of("fixtures","pdf",name+".pdf"));}
    private static PdfImportController.SelectedImage selectedImage(int color)throws Exception {
        var image=new BufferedImage(48,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,color);
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        return new PdfImportController.SelectedImage("image/png",Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }
    private static Set<String> attachmentNames()throws Exception {
        Path root=Path.of("target/integration-data/attachments");
        if(!Files.exists(root))return Set.of();
        try(var paths=Files.list(root)){return paths.map(path->path.getFileName().toString()).collect(Collectors.toSet());}
    }
    @Test void previewWritesNothingAndCreationMakesOneInitialVersion()throws Exception {
        isolated();int before=resumes.list().size();
        var body=mvc.perform(multipart("/api/imports/pdf/preview").file(new MockMultipartFile("file","简历.pdf","application/pdf",fixture("text"))).header("X-Local-Resume","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.format").value("pdf"))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(resumes.list()).hasSize(before);
        var preview=mapper.readValue(body,PdfImports.Preview.class);var token=UUID.randomUUID();
        var request=new PdfImportController.Create(token,preview.title(),preview.document());
        var created=mapper.readValue(mvc.perform(post("/api/imports/pdf/create").header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsBytes(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mutationId").value(token.toString()))
            .andReturn().getResponse().getContentAsByteArray(),DocxImports.Created.class);
        assertThat(created.resume().revision()).isEqualTo(1);
        assertThat(created.resume().document()).isEqualTo(preview.document());
        assertThat(resumes.versions(created.resume().id())).hasSize(1);
        assertThat(imports.create(request).resume().id()).isEqualTo(created.resume().id());
        assertThat(resumes.list()).hasSize(before+1);
    }
    @Test void exactRetryReadsEditsWhileChangedPayloadConflictsAndDeletionStaysGone() {
        isolated();var request=new PdfImportController.Create(UUID.randomUUID(),"PDF 导入",ResumeDocument.sample("blank"));
        var first=imports.create(request);
        var edited=resumes.save(first.resume().id(),new ResumeService.Save("已编辑",first.resume().document(),1,UUID.randomUUID()));
        assertThat(imports.create(request).resume()).isEqualTo(edited);
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(request.mutationId(),"不同标题",request.document())))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMPORT_CONFLICT"));
        resumes.delete(first.resume().id(),2);
        assertThat(jdbc.queryForObject("SELECT resume_id IS NULL FROM document_imports WHERE mutation_id=?",Boolean.class,request.mutationId())).isTrue();
        assertThatThrownBy(()->imports.create(request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMPORT_DELETED"));
    }
    @Test void selectedImagesBelongToTheInitialVersionAndExactRetryDoesNotUploadAgain()throws Exception {
        isolated();var document=ResumeDocument.sample("blank");var token=UUID.randomUUID();
        var request=new PdfImportController.Create(token,"图片核对导入",document,
            selectedImage(0xffda722c),selectedImage(0xff2b74a6));
        var first=imports.create(request).resume();
        String photo=first.document().layout().photo().id(),logo=first.document().layout().logo().id();
        assertThat(photo).isNotNull().isNotEqualTo(logo);assertThat(logo).isNotNull();
        assertThat(resumes.versions(first.id())).hasSize(1);
        assertThat(resumes.version(first.id(),resumes.versions(first.id()).getFirst().id()).document()).isEqualTo(first.document());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments WHERE id IN (?,?)",Integer.class,UUID.fromString(photo),UUID.fromString(logo))).isEqualTo(2);
        var retry=imports.create(request).resume();
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.document().layout().photo().id()).isEqualTo(photo);
        assertThat(retry.document().layout().logo().id()).isEqualTo(logo);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments WHERE id IN (?,?)",Integer.class,UUID.fromString(photo),UUID.fromString(logo))).isEqualTo(2);
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(token,"图片核对导入",document,
            selectedImage(0xff117744),request.logo())))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMPORT_CONFLICT"));
    }
    @Test void imageSelectionPreservesAnUnselectedHiddenSlot()throws Exception {
        isolated();var original=ResumeDocument.sample("blank");var layout=original.layout();var logo=layout.logo();
        var hiddenLogo=new ResumeDraft.ImageSlot(null,false,logo.widthMm(),logo.heightMm(),logo.fit(),logo.quarterTurns(),logo.zoom(),logo.positionX(),logo.positionY());
        var document=new ResumeDocument(4,original.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),layout.swapImages(),layout.photo(),hiddenLogo,layout.presentation()));
        var created=imports.create(new PdfImportController.Create(UUID.randomUUID(),"隐藏图片槽",document,selectedImage(0xffda722c),null)).resume();
        assertThat(created.document().layout().photo().id()).isNotNull();
        assertThat(created.document().layout().logo().id()).isNull();
        assertThat(created.document().layout().logo().visible()).isFalse();
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failedSecondSelectedImageRollsBackReceiptAndFirstImage()throws Exception {
        isolated();var before=attachmentNames();int resumesBefore=resumes.list().size();var mutation=UUID.randomUUID();
        var corrupt=new byte[]{(byte)137,80,78,71,13,10,26,10,1,2,3};
        var request=new PdfImportController.Create(mutation,"损坏图片",ResumeDocument.sample("blank"),
            selectedImage(0xffda722c),new PdfImportController.SelectedImage("image/png",Base64.getEncoder().encodeToString(corrupt)));
        assertThatThrownBy(()->imports.create(request)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("CORRUPT_IMAGE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_imports WHERE mutation_id=?",Integer.class,mutation)).isZero();
        assertThat(resumes.list()).hasSize(resumesBefore);
        assertThat(attachmentNames()).isEqualTo(before);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void simultaneousRetriesCreateOneResumeAndOneVersion()throws Exception {
        isolated();var request=new PdfImportController.Create(UUID.randomUUID(),"并发 PDF",ResumeDocument.sample("blank"));
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);UUID createdId=null;
        try {
            Callable<DocxImports.Created> work=()->{start.await();return imports.create(request);};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            createdId=a.get(20,TimeUnit.SECONDS).resume().id();
            assertThat(b.get(20,TimeUnit.SECONDS).resume().id()).isEqualTo(createdId);
            assertThat(resumes.versions(createdId)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_imports WHERE mutation_id=?",Integer.class,request.mutationId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();pool.awaitTermination(20,TimeUnit.SECONDS);
            var ids=jdbc.queryForList("SELECT resume_id FROM document_imports WHERE mutation_id=?",UUID.class,request.mutationId());
            jdbc.update("DELETE FROM document_imports WHERE mutation_id=?",request.mutationId());
            for(var id:ids)if(id!=null)jdbc.update("DELETE FROM resumes WHERE id=?",id);
        }
    }
    @Test void portableBackupOmitsReceiptTokenAndSourcePdf()throws Exception {
        isolated();var request=new PdfImportController.Create(UUID.randomUUID(),"PDF 导入",ResumeDocument.sample("blank"));
        var created=imports.create(request);var saved=backups.create();
        try(var zip=new ZipFile(backups.download(saved.id()).toFile())) {
            assertThat(zip.stream().map(java.util.zip.ZipEntry::getName)).noneMatch(n->n.contains("document_imports")||n.startsWith("imports/"));
            var workspace=zip.getEntry("workspace.json");
            var content=new String(zip.getInputStream(workspace).readAllBytes(),StandardCharsets.UTF_8);
            assertThat(content).doesNotContain(request.mutationId().toString());
            assertThat(mapper.readTree(content).path("resumes").findValuesAsText("id")).contains(created.resume().id().toString());
        }
    }
}
