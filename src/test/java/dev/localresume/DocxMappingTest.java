package dev.localresume;

import java.util.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DocxMappingTest {
    static DocxReader.Result source(List<DocxReader.Block> blocks) {return new DocxReader.Result(blocks,"",List.of(),new DocxReader.Statistics(blocks.size(),0,0));}
    static DocxReader.Block block(String text) {return new DocxReader.Block(text,false,false);}
    @Test void mapsOnlyRecognizedFieldsAndHeadingsAndRetainsEverythingElse() {
        var source=new DocxReader().read(DocxFixtures.document(DocxFixtures.paragraph("姓名：奶龙")+DocxFixtures.paragraph("求职方向：Java 实习")+DocxFixtures.paragraph("邮箱：nailong@example.invalid")+DocxFixtures.paragraph("电话：13800000000")+DocxFixtures.paragraph("城市：杭州")+DocxFixtures.paragraph("教育背景")+DocxFixtures.paragraph("大学内容")+DocxFixtures.paragraph("项目经历")+DocxFixtures.paragraph("项目内容")+DocxFixtures.paragraph("专业技能")+DocxFixtures.paragraph("Java")+DocxFixtures.paragraph("未分类的其他原文")));
        var result=new DocxMapping().map(source);var content=result.document().content();
        assertThat(content.name()).isEqualTo("奶龙");assertThat(content.headline()).isEqualTo("Java 实习");assertThat(content.email()).isEqualTo("nailong@example.invalid");assertThat(content.phone()).isEqualTo("13800000000");assertThat(content.location()).isEqualTo("杭州");
        assertThat(content.sections()).extracting(ResumeDocument.Section::type).containsExactly("education","project","skills");
        assertThat(text(result.document())).contains("大学内容","项目内容","Java","未分类的其他原文");
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("DOCX_FIELDS_INFERRED");
        try(var validator=Validation.buildDefaultValidatorFactory()){assertThat(validator.getValidator().validate(result.document())).isEmpty();}
    }
    static String text(ResumeDocument document) {return String.join("",document.content().sections().stream().flatMap(s->s.entries().stream()).flatMap(e->e.bullets().stream()).toList());}
    @Test void splitsUnicodeWithoutLossAndDistributesEntriesAndSections() {
        String longText="x".repeat(799)+"😀"+"中".repeat(1600);var blocks=new ArrayList<DocxReader.Block>();blocks.add(block(longText));for(int i=0;i<500;i++)blocks.add(block("paragraph "+i));
        var result=new DocxMapping().map(source(blocks));
        assertThat(text(result.document())).isEqualTo(longText+String.join("",blocks.subList(1,blocks.size()).stream().map(DocxReader.Block::text).toList()));
        assertThat(result.document().content().sections()).hasSize(2);
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("DOCX_PARAGRAPHS_SPLIT","DOCX_UNCLASSIFIED","DOCX_FIELDS_MISSING");
        try(var validator=Validation.buildDefaultValidatorFactory()){assertThat(validator.getValidator().validate(result.document())).isEmpty();}
    }
    @Test void retainsHeaderContactTextUnknownHeadingsAndOverlongFields() {
        var result=new DocxMapping().map(source(List.of(new DocxReader.Block("姓名：页眉",false,true),block("姓名："+"长".repeat(31)),new DocxReader.Block("其他能力",true,false),block("原文内容"))));
        assertThat(result.document().content().name()).isEqualTo("姓名");
        assertThat(text(result.document())).contains("姓名：页眉","姓名："+"长".repeat(31),"原文内容");
        assertThat(result.document().content().sections()).anyMatch(s->s.title().equals("其他能力")&&s.type().equals("custom"));
    }
    @Test void rejectsUnrepresentableContentWithoutTruncation() {
        var blocks=new ArrayList<DocxReader.Block>();for(int i=0;i<21;i++){blocks.add(new DocxReader.Block("Heading "+i,true,false));blocks.add(block("content"));}
        assertThatThrownBy(()->new DocxMapping().map(source(blocks))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("DOCX_CONTENT_TOO_LARGE"));
    }
    @Test void recognizesEnglishExperienceAndPreservesLongWhitespaceAroundHeadings() {
        var result=new DocxMapping().map(source(List.of(block("Work Experience"),block("Worked on Java"),new DocxReader.Block(" ".repeat(50)+"其他能力",true,false),block("原文"))));
        assertThat(result.document().content().sections().getFirst().type()).isEqualTo("experience");
        assertThat(text(result.document())).contains(" ".repeat(50)+"其他能力");
        try(var validator=Validation.buildDefaultValidatorFactory()){assertThat(validator.getValidator().validate(result.document())).isEmpty();}
    }
}
