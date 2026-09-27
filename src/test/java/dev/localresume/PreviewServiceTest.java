package dev.localresume;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
class PreviewServiceTest {
    @Autowired PreviewService previews;
    @MockitoBean AttachmentStorage storage;
    @MockitoBean org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Test void rendersBothAssetsEscapesTextAndKeepsPreviousSnapshotStable() {
        var photo = new ResumeDraft.ImageSlot("00000000-0000-0000-0000-000000000001", true, 26, 34, "cover", 0, 1, 50, 50);
        var logo = new ResumeDraft.ImageSlot("00000000-0000-0000-0000-000000000002", true, 26, 26, "contain", 0, 1, 50, 50);
        var first = previews.create(new ResumeDraft(1,"two","<奶龙>","Java","","","",false,photo,logo));
        assertThat(first.html()).contains("&lt;奶龙&gt;", "/api/assets/" + photo.id() + "/image", "/api/assets/" + logo.id() + "/image", "data-break=\"true\"");
        previews.create(new ResumeDraft(1,"one","新名称","Java","","","",true,photo,logo));
        assertThat(previews.get(first.id()).html()).isEqualTo(first.html()).doesNotContain("新名称");
    }
}
