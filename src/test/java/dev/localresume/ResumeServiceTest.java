package dev.localresume;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.hikari.schema=resume_test", "spring.flyway.schemas=resume_test", "spring.flyway.default-schema=resume_test", "resume.data-dir=./target/integration-data"})
@Transactional
class ResumeServiceTest {
    @Autowired ResumeService service;
    @Autowired ImageService images;
    @Autowired AttachmentStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Test void savesRenamesDuplicatesAndRejectsStaleWrites() {
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo("resume_test");
        var original=service.create("基础简历",ResumeDocument.sample("one"));
        UUID mutation=UUID.randomUUID();
        var saved=service.save(original.id(),new ResumeService.Save("Java 岗",original.document(),1,mutation));
        assertThat(saved.revision()).isEqualTo(2);
        assertThat(service.save(original.id(),new ResumeService.Save("Java 岗",original.document(),1,mutation)).revision()).isEqualTo(2);
        assertThatThrownBy(()->service.save(original.id(),new ResumeService.Save("旧页面",original.document(),1,UUID.randomUUID())))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("REVISION_CONFLICT"));
        assertThat(service.get(original.id()).title()).isEqualTo("Java 岗");
        var copy=service.duplicate(original.id(),2,"AI 岗");
        assertThat(copy.document()).isEqualTo(original.document()); assertThat(copy.id()).isNotEqualTo(original.id());
        service.delete(copy.id(),1);
        assertThatThrownBy(()->service.get(copy.id())).isInstanceOf(ApiException.class);
        assertThat(service.get(original.id()).title()).isEqualTo("Java 岗");
    }
    @Test void restoreKeepsPriorStateAndHistoricalPhoto() throws Exception {
        var asset=images.importImage(Files.readAllBytes(Path.of("fixtures/portrait-exif-6.jpg")));
        var doc=ResumeDocument.sample("one"); var l=doc.layout();
        var withPhoto=new ResumeDocument(2,doc.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),false,
            new ResumeDraft.ImageSlot(asset.id(),true,26,34,"cover",0,1,50,50),l.logo()));
        var resume=service.create("有照片",withPhoto);
        var snapshot=service.checkpoint(resume.id(),1,"第一版");
        service.save(resume.id(),new ResumeService.Save("已移除照片",doc,1,UUID.randomUUID()));
        assertThat(service.get(resume.id()).document().layout().photo().id()).isNull();
        var restored=service.restore(resume.id(),snapshot.id(),2);
        assertThat(restored.revision()).isEqualTo(3);
        assertThat(restored.document().layout().photo().id()).isEqualTo(asset.id());
        assertThat(storage.image(asset.id())).isNotEmpty();
        assertThat(service.versions(resume.id())).anyMatch(v->v.label().startsWith("恢复前自动保留"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM version_assets WHERE asset_id=?",Integer.class,UUID.fromString(asset.id()))).isGreaterThan(0);
    }
    @Test void restoreCannotReadAnotherResumesVersion() {
        var a=service.create("A",ResumeDocument.sample("one")); var b=service.create("B",ResumeDocument.sample("one"));
        var v=service.versions(a.id()).getFirst();
        assertThatThrownBy(()->service.restore(b.id(),v.id(),1)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("VERSION_NOT_FOUND"));
        assertThatThrownBy(()->service.version(b.id(),v.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("VERSION_NOT_FOUND"));
        assertThatThrownBy(()->service.version(a.id(),UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("VERSION_NOT_FOUND"));
    }
    @Test void viewingVersionReadsItsOriginalDocumentWithoutChangingCurrentState() {
        var original=service.create("原始名称",ResumeDocument.sample("one"));
        var snapshot=service.checkpoint(original.id(),1,"投递前");
        service.save(original.id(),new ResumeService.Save("新版名称",ResumeDocument.sample("two"),1,UUID.randomUUID()));
        var current=service.get(original.id());var versions=service.versions(original.id());
        var detail=service.version(original.id(),snapshot.id());
        assertThat(detail.title()).isEqualTo("原始名称");assertThat(detail.label()).isEqualTo("投递前");
        assertThat(detail.document()).isEqualTo(original.document());assertThat(detail.sourceRevision()).isEqualTo(1);
        assertThat(detail.createdAt()).isEqualTo(versions.stream().filter(v->v.id().equals(snapshot.id())).findFirst().orElseThrow().createdAt());
        assertThat(service.get(original.id())).isEqualTo(current);assertThat(service.versions(original.id())).isEqualTo(versions);
    }
    @Test void markupOnlyAllowsBoldAndEscapesHtml() {
        String html=new RichText().render("**重点** <img src=x onerror=alert(1)> [link](javascript:alert(1))");
        assertThat(html).contains("<strong>重点</strong>","&lt;img").doesNotContain("<img","<a");
    }
    @Test void reviewedParagraphCreatesOneSafetyVersionAndLostAcknowledgementCanRetry(){
        var original=service.create("润色确认测试",ResumeDocument.sample("one"));var section=original.document().content().sections().getFirst();var entry=section.entries().getFirst();String before=entry.bullets().getFirst();UUID token=UUID.randomUUID();int count=service.versions(original.id()).size();
        var applied=service.applyParagraph(original.id(),1,section.id(),entry.id(),0,before,"测试新的表达",token);
        assertThat(applied.revision()).isEqualTo(2);assertThat(service.versions(original.id())).hasSize(count+1);
        var safety=service.versions(original.id()).stream().filter(v->v.label().startsWith("AI 应用前")).findFirst().orElseThrow();assertThat(service.version(original.id(),safety.id()).document()).isEqualTo(original.document());
        assertThat(service.applyParagraph(original.id(),1,section.id(),entry.id(),0,before,"测试新的表达",token)).isEqualTo(applied);assertThat(service.versions(original.id())).hasSize(count+1);
        assertThat(applied.document().layout()).isEqualTo(original.document().layout());assertThat(applied.document().content().phone()).isEqualTo(original.document().content().phone());
    }
    @Test void staleOrMismatchedParagraphDoesNotCreateVersionOrOverwriteAnotherEdit(){
        var original=service.create("旧建议检查",ResumeDocument.sample("one"));var section=original.document().content().sections().getFirst();var entry=section.entries().getFirst();int count=service.versions(original.id()).size();
        assertThatThrownBy(()->service.applyParagraph(original.id(),1,section.id(),entry.id(),0,"不匹配的原文","新文字",UUID.randomUUID())).isInstanceOf(ApiException.class);assertThat(service.versions(original.id())).hasSize(count);assertThat(service.get(original.id())).isEqualTo(original);
        var changed=service.save(original.id(),new ResumeService.Save("另一窗口修改",original.document(),1,UUID.randomUUID()));
        assertThatThrownBy(()->service.applyParagraph(original.id(),1,section.id(),entry.id(),0,entry.bullets().getFirst(),"新文字",UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("REVISION_CONFLICT"));assertThat(service.get(original.id())).isEqualTo(changed);assertThat(service.versions(original.id())).hasSize(count);
    }
}
