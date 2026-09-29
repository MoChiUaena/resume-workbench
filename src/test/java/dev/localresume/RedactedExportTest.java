package dev.localresume;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.hikari.schema=resume_test","spring.flyway.schemas=resume_test","spring.flyway.default-schema=resume_test","resume.data-dir=./target/integration-data"})
@Transactional
class RedactedExportTest {
    @Autowired ResumeService resumes;
    @Autowired ResumeController controller;
    @Test void exportPreviewIsReadOnlyAndRejectsMissingChoicesAndStaleSource() {
        var source=resumes.create("合成脱敏简历",ResumeDocument.sample("two"));var versions=resumes.versions(source.id());
        @SuppressWarnings("unchecked") var preview=(Map<String,Object>)controller.exportPreview(source.id(),new ResumeController.ExportRequest(1,Redaction.Options.defaults(),null));
        assertThat(preview.get("url")).asString().startsWith("/render/");assertThat(preview.get("digest")).asString().hasSize(64);
        assertThat(resumes.get(source.id())).isEqualTo(source);assertThat(resumes.versions(source.id())).isEqualTo(versions);
        assertThatThrownBy(()->controller.exportPreview(source.id(),new ResumeController.ExportRequest(1,null,null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("REDACTION_OPTIONS_REQUIRED"));
        resumes.save(source.id(),new ResumeService.Save(source.title(),source.document(),1,UUID.randomUUID()));
        assertThatThrownBy(()->controller.exportPreview(source.id(),new ResumeController.ExportRequest(1,Redaction.Options.defaults(),null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("REVISION_CONFLICT"));
    }
    @Test void missingOrChangedPreviewIsRejectedBeforeCreatingAnExportVersion() {
        var source=resumes.create("原稿",ResumeDocument.sample("one"));var versions=resumes.versions(source.id());
        assertThatThrownBy(()->controller.export(source.id(),new ResumeController.ExportRequest(1,Redaction.Options.defaults(),null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("EXPORT_PREVIEW_REQUIRED"));
        assertThatThrownBy(()->controller.export(source.id(),new ResumeController.ExportRequest(1,Redaction.Options.defaults(),"0".repeat(64))))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("EXPORT_PREVIEW_CHANGED"));
        assertThat(resumes.get(source.id())).isEqualTo(source);assertThat(resumes.versions(source.id())).isEqualTo(versions);
    }
}
