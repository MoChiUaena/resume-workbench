package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PdfImportControllerTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final PdfImports imports=new PdfImports(new DocumentImportReceipts(null,null,mapper,Validation.buildDefaultValidatorFactory().getValidator()));
    private static byte[] fixture(String name) throws Exception {return Files.readAllBytes(Path.of("fixtures","pdf",name+".pdf"));}
    @Test void previewReturnsReviewableSchemaWithoutDatabase() throws Exception {
        var preview=imports.preview(new MockMultipartFile("file","C:\\private\\简历.pdf","application/pdf",fixture("text")));
        assertThat(preview.format()).isEqualTo("pdf");
        assertThat(preview.fileName()).isEqualTo("简历.pdf");
        assertThat(preview.title()).isEqualTo("简历");
        assertThat(preview.sourceText()).contains("奶龙","教育背景","Java、PostgreSQL");
        assertThat(preview.document().content().sections()).anyMatch(s->s.type().equals("education"));
        assertThat(preview.statistics().pages()).isEqualTo(1);
    }
    @Test void previewRouteRequiresLocalHeaderAndReportsFileErrors() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new PdfImportController(imports)).setControllerAdvice(new ApiErrors()).addFilters(new LocalRequestFilter()).build();
        mvc.perform(multipart("/api/imports/pdf/preview").file(new MockMultipartFile("file","ok.pdf","",fixture("text"))))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/imports/pdf/preview").header("X-Local-Resume","1"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PDF_INVALID"));
        mvc.perform(multipart("/api/imports/pdf/preview").file(new MockMultipartFile("file","ok.pdf","",fixture("text"))).header("X-Local-Resume","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.format").value("pdf"));
    }
    @Test void createValidationUsesPdfSpecificCodesBeforeDatabase() {
        var doc=ResumeDocument.sample("blank");
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(UUID.randomUUID(),"x".repeat(121),doc)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_CONTENT_TOO_LARGE"));
        var layout=doc.layout();
        var image=new ResumeDocument(4,doc.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),false,new ResumeDraft.ImageSlot(UUID.randomUUID().toString(),true,26,34,"cover",0,1,50,50),layout.logo()));
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(UUID.randomUUID(),"x",image)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_INVALID"));
    }
    @Test void selectedPdfImageMustBeBoundedAndMatchItsDeclaredFormatBeforeDatabase() {
        var doc=ResumeDocument.sample("blank");var token=UUID.randomUUID();
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(token,"PDF",doc,
            new PdfImportController.SelectedImage(null,"iVBORw0KGgo="),null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMAGE_INVALID"));
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(token,"PDF",doc,
            new PdfImportController.SelectedImage("image/png","not-base64"),null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMAGE_INVALID"));
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(token,"PDF",doc,
            new PdfImportController.SelectedImage("image/png",java.util.Base64.getEncoder().encodeToString("not an image".getBytes())),null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMAGE_INVALID"));
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(token,"PDF",doc,
            new PdfImportController.SelectedImage("image/png","A".repeat(699052)),null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMAGE_TOO_LARGE"));
        assertThatThrownBy(()->imports.create(new PdfImportController.Create(token,"PDF",doc,
            new PdfImportController.SelectedImage("image/png","A".repeat(699056)),null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("PDF_IMAGE_TOO_LARGE"));
    }
    @Test void createRouteReportsPdfDocumentBoundsBeforeDatabase() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new PdfImportController(imports)).setControllerAdvice(new ApiErrors()).build();
        var request=new PdfImportController.Create(UUID.randomUUID(),"x".repeat(121),ResumeDocument.sample("blank"));
        mvc.perform(post("/api/imports/pdf/create").contentType("application/json").content(mapper.writeValueAsBytes(request)))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PDF_CONTENT_TOO_LARGE"));
    }
    @Test void multipartEnvelopeErrorUsesPdfSizeContext() {
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/imports/pdf/preview");
        var response=new ApiErrors().size(request);
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().toString()).contains("PDF_TOO_LARGE","5 MiB");
    }
}
