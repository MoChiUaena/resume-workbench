package dev.localresume;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.hikari.schema=quarantine_preview_test","spring.flyway.schemas=quarantine_preview_test","spring.flyway.default-schema=quarantine_preview_test","resume.data-dir=./target/quarantine-preview-test-files"})
@Transactional
class StoragePreviewServiceTest {
    @Autowired StoragePreviewService inventory;
    @Autowired ResumeService resumes;
    @Autowired ImageService images;
    @Autowired JdbcTemplate jdbc;
    @Test void hiddenAndHistoricalImageReferencesAreProtectedAndBrokenLinksFailClosed()throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("quarantine_preview_test");
        var asset=images.importImage(Files.readAllBytes(Path.of("fixtures/university-logo.png")));var plain=ResumeDocument.sample("one");var layout=plain.layout();
        var photo=new ResumeDraft.ImageSlot(asset.id(),false,26,34,"cover",0,1,50,50);
        var document=new ResumeDocument(ResumeDocument.SCHEMA_VERSION,plain.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),false,photo,layout.logo(),layout.presentation()));
        var source=resumes.create("合成引用测试",document);var first=inventory.references();assertThat(first.consistent()).isTrue();assertThat(first.currentImages()).contains(asset.id());assertThat(first.historicalImages()).contains(asset.id());
        resumes.save(source.id(),new ResumeService.Save(source.title(),plain,1,UUID.randomUUID()));var historical=inventory.references();assertThat(historical.currentImages()).doesNotContain(asset.id());assertThat(historical.historicalImages()).contains(asset.id());assertThat(historical.fingerprint()).isNotEqualTo(first.fingerprint());
        jdbc.update("DELETE FROM version_assets WHERE asset_id=?",UUID.fromString(asset.id()));var broken=inventory.references();assertThat(broken.consistent()).isFalse();assertThat(broken.historicalImages()).contains(asset.id());
        resumes.delete(source.id(),2);var deleted=inventory.references();assertThat(deleted.consistent()).isTrue();assertThat(deleted.historicalImages()).doesNotContain(asset.id());assertThat(deleted.catalogs()).containsKey(asset.id());
    }
    @Test void unreadableStoredDocumentsNeverProduceACompleteReferenceCheck(){
        var source=resumes.create("合成损坏文档",ResumeDocument.sample("one"));String fingerprint=inventory.references().fingerprint();
        jdbc.update("UPDATE resumes SET document=jsonb_set(document,'{content,name}','\"PRIVATE-NAME-CANARY\"') WHERE id=?",source.id());assertThat(inventory.references().fingerprint()).isEqualTo(fingerprint);
        jdbc.update("UPDATE resumes SET document=jsonb_set(document,'{schemaVersion}','999') WHERE id=?",source.id());assertThat(inventory.references().consistent()).isFalse();
        jdbc.update("UPDATE resumes SET document='{}'::jsonb WHERE id=?",source.id());assertThat(inventory.references().consistent()).isFalse();
    }
}
