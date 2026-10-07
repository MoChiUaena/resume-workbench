package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocxImportControllerTest {
    private final DocxImports imports=new DocxImports(null,null,new ObjectMapper(),Validation.buildDefaultValidatorFactory().getValidator());
    @Test void previewSanitizesBasenameAndReturnsRealSchemaWithoutDatabase() {
        var preview=imports.preview(new MockMultipartFile("file","C:\\private\\简历.docx","application/octet-stream",DocxFixtures.document(DocxFixtures.paragraph("教育背景")+DocxFixtures.paragraph("大学内容"))));
        assertThat(preview.fileName()).isEqualTo("简历.docx");assertThat(preview.title()).isEqualTo("简历");assertThat(preview.format()).isEqualTo("docx");assertThat(preview.sourceText()).contains("大学内容");assertThat(preview.document().content().sections()).anyMatch(s->s.type().equals("education"));
    }
    @Test void rejectsWrongExtensionAndActualUploadSize() {
        assertThatThrownBy(()->imports.preview(new MockMultipartFile("file","old.doc","",new byte[]{1}))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_UNSUPPORTED"));
        assertThatThrownBy(()->imports.preview(new MockMultipartFile("file","large.docx","",new byte[5*1024*1024+1]))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_TOO_LARGE"));
    }
    @Test void validatesCreateImagesDistinctIdsAndEncodedBoundsBeforeDatabase() {
        var doc=ResumeDocument.sample("blank");var layout=doc.layout();
        var image=new ResumeDocument(4,doc.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),false,new ResumeDraft.ImageSlot(UUID.randomUUID().toString(),true,26,34,"cover",0,1,50,50),layout.logo()));
        assertThatThrownBy(()->imports.create(new DocxImportController.Create(UUID.randomUUID(),"x",image))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_INVALID"));
        var section=new ResumeDocument.Section("same","custom","内容",true,false,List.of(new ResumeDocument.Entry("same","","",false,List.of("x"))));
        var duplicate=new ResumeDocument(4,new ResumeDocument.Content("姓名","","","","",List.of(section)),layout);
        assertThatThrownBy(()->imports.create(new DocxImportController.Create(UUID.randomUUID(),"x",duplicate))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_INVALID"));
        var huge=new ResumeDocument(4,new ResumeDocument.Content("姓名","","","","",List.of(new ResumeDocument.Section("section","custom","内容",true,false,List.of(new ResumeDocument.Entry("entry","","",false,List.of("x".repeat(801))))))),layout);
        assertThatThrownBy(()->imports.create(new DocxImportController.Create(UUID.randomUUID(),"x",huge))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_CONTENT_TOO_LARGE"));
    }
    @Test void localOnlyPreviewAndInvalidCreationHaveStructuredResponses()throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new DocxImportController(imports)).setControllerAdvice(new ApiErrors()).addFilters(new LocalRequestFilter()).build();
        mvc.perform(multipart("/api/imports/docx/preview").file(new MockMultipartFile("file","bad.docx","",new byte[]{1}))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/imports/docx/preview").file(new MockMultipartFile("file","bad.docx","",new byte[]{1})).header("X-Local-Resume","1")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DOCX_UNSUPPORTED"));
        mvc.perform(post("/api/imports/docx/create").header("X-Local-Resume","1").contentType("application/json").content("{\"title\":\"x\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(multipart("/api/imports/docx/preview").header("X-Local-Resume","1")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DOCX_INVALID"));
    }
    @Test void rejectsEncodedDocumentBoundEvenWhenEachFieldIsSchemaValid() {
        var entries=new ArrayList<ResumeDocument.Entry>();for(int i=0;i<9;i++)entries.add(new ResumeDocument.Entry("entry"+i,"","",false,Collections.nCopies(30,"x".repeat(800))));
        var doc=new ResumeDocument(4,new ResumeDocument.Content("姓名","","","","",List.of(new ResumeDocument.Section("section","custom","内容",true,false,entries))),ResumeDocument.sample("blank").layout());
        assertThatThrownBy(()->imports.create(new DocxImportController.Create(UUID.randomUUID(),"x",doc))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_CONTENT_TOO_LARGE"));
    }
    @Test void controllerReportsSchemaContentBoundsAsImportSizeError()throws Exception {
        var layout=ResumeDocument.sample("blank").layout();
        var doc=new ResumeDocument(4,new ResumeDocument.Content("姓名","","","","",List.of(new ResumeDocument.Section("s","custom","内容",true,false,List.of(new ResumeDocument.Entry("e","","",false,List.of("x".repeat(801))))))),layout);
        var mvc=MockMvcBuilders.standaloneSetup(new DocxImportController(imports)).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(post("/api/imports/docx/create").contentType("application/json").content(new ObjectMapper().writeValueAsBytes(new DocxImportController.Create(UUID.randomUUID(),"x",doc))))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("DOCX_CONTENT_TOO_LARGE"));
    }
    @Test void multipartEnvelopeErrorUsesWordSizeContextAndKeepsImageContext() {
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/imports/docx/preview");
        var response=new ApiErrors().size(request);assertThat(response.getStatusCode().value()).isEqualTo(413);assertThat(response.getBody().toString()).contains("DOCX_TOO_LARGE","5 MiB");
        request.setRequestURI("/api/images");assertThat(new ApiErrors().size(request).getBody().toString()).contains("FILE_TOO_LARGE");
    }
    @Test void filenamesStayBoundedAtUnicodeBoundariesAndActualReadCannotTrustDeclaredSize() {
        var preview=imports.preview(new MockMultipartFile("file","../"+"😀".repeat(100)+".docx","",DocxFixtures.document(DocxFixtures.paragraph("原文"))));
        assertThat(preview.fileName()).hasSizeLessThanOrEqualTo(120);assertThat(preview.title()).hasSizeLessThanOrEqualTo(120);assertThat(preview.fileName()).doesNotContain("/");
        assertThat(Character.isHighSurrogate(preview.fileName().charAt(preview.fileName().length()-1))).isFalse();
        var lyingFile=new MockMultipartFile("file","large.docx","",new byte[5*1024*1024+1]){@Override public long getSize(){return 1;}};
        assertThatThrownBy(()->imports.preview(lyingFile)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_TOO_LARGE"));
    }
}
