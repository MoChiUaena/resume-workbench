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
    @Test void pdfGenericResumeTitleDoesNotHideAFollowingPlausibleName() {
        var result=new DocxMapping().map(List.of(block("个人简历"),block("奶龙"),block("教育背景"),block("示例大学")),List.of(),"PDF");
        assertThat(result.document().content().name()).isEqualTo("奶龙");
        assertThat(result.document().content().sections()).anyMatch(s->s.type().equals("education"));
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("PDF_NAME_INFERRED");
    }
    @Test void pdfSectionHeadingIsNeverInferredAsName() {
        var result=new DocxMapping().map(List.of(block("教育背景"),block("示例大学")),List.of(),"PDF");
        assertThat(result.document().content().name()).isEqualTo("姓名");
        assertThat(result.document().content().sections().getFirst().type()).isEqualTo("education");
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).doesNotContain("PDF_NAME_INFERRED");
    }
    @Test void pdfNameInferenceDoesNotClaimAnExplicitLabelWasFound() {
        var result=new DocxMapping().map(List.of(block("奶龙"),block("简介内容")),List.of(),"PDF");
        assertThat(result.document().content().name()).isEqualTo("奶龙");
        assertThat(result.warnings()).extracting(DocxReader.Warning::code)
            .contains("PDF_NAME_INFERRED").doesNotContain("PDF_FIELDS_INFERRED");
    }
    @Test void pdfHeaderContactLineFillsEmailAndPhoneWithoutRepeatingThemInBody() {
        var result=new DocxMapping().map(List.of(block("奶龙"),block("手机：13800000000 | 邮箱：nailong@example.invalid"),
            block("教育背景"),block("示例大学")),List.of(),"PDF");
        var content=result.document().content();
        assertThat(content.email()).isEqualTo("nailong@example.invalid");
        assertThat(content.phone()).isEqualTo("13800000000");
        assertThat(text(result.document())).doesNotContain("13800000000","nailong@example.invalid");
        assertThat(content.sections()).extracting(ResumeDocument.Section::type).containsExactly("education");
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("PDF_CONTACTS_INFERRED");
    }
    @Test void pdfMixedHeaderContactsRemainUnchangedForReview() {
        var result=new DocxMapping().map(List.of(block("奶龙"),block("nailong@example.invalid / 13800000000 / 2028 届"),
            block("教育背景")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEmpty();
        assertThat(result.document().content().phone()).isEmpty();
        assertThat(text(result.document())).contains("2028 届","nailong@example.invalid","13800000000");
    }
    @Test void pdfContactCleanupDoesNotChangeOtherWordsContainingContactLabels() {
        var result=new DocxMapping().map(List.of(block("奶龙"),
            block("电话：13800000000 | 邮箱：nailong@example.invalid | 电话亭项目")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEmpty();
        assertThat(result.document().content().phone()).isEmpty();
        assertThat(text(result.document())).contains("电话亭项目");
    }
    @Test void pdfContactValuesInAnExperienceSectionAreNotPersonalContacts() {
        var result=new DocxMapping().map(List.of(block("奶龙"),block("工作经历"),
            block("客户电话 13800000000 / 客服邮箱 service@example.invalid")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEmpty();
        assertThat(result.document().content().phone()).isEmpty();
        assertThat(text(result.document())).contains("13800000000","service@example.invalid");
    }
    @Test void pdfCompactCombinedContactsAreNotAcceptedAsOneEmailField() {
        var result=new DocxMapping().map(List.of(block("奶龙"),
            block("邮箱：nailong@example.invalid|手机：13800000000")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEqualTo("nailong@example.invalid");
        assertThat(result.document().content().phone()).isEqualTo("13800000000");
    }
    @Test void pdfUnclassifiedIntroStopsPersonalContactInference() {
        var result=new DocxMapping().map(List.of(block("奶龙"),block("个人简介"),
            block("客服热线 13800000000 / 客服邮箱 support@example.invalid")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEmpty();
        assertThat(result.document().content().phone()).isEmpty();
        assertThat(text(result.document())).contains("13800000000","support@example.invalid");
    }
    @Test void pdfAmbiguousHeaderContactsStayInReviewText() {
        var result=new DocxMapping().map(List.of(block("奶龙"),
            block("邮箱：nailong@example.invalid|手机：13800000000|备用：13900000000")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEmpty();
        assertThat(result.document().content().phone()).isEmpty();
        assertThat(text(result.document())).contains("nailong@example.invalid","13800000000","13900000000");
    }
    @Test void pdfGenericTitleBeforeNameAndPureContactsStillInfersContacts() {
        var result=new DocxMapping().map(List.of(block("个人简历"),block("奶龙"),
            block("邮箱：nailong@example.invalid|手机：13800000000")),List.of(),"PDF");
        assertThat(result.document().content().name()).isEqualTo("奶龙");
        assertThat(result.document().content().email()).isEqualTo("nailong@example.invalid");
        assertThat(result.document().content().phone()).isEqualTo("13800000000");
    }
    @Test void pdfEarlyServiceContactLineIsNotApplicantsContact() {
        var result=new DocxMapping().map(List.of(block("姓名：奶龙"),
            block("客服热线 13800000000 / 客服邮箱 support@example.invalid")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEmpty();
        assertThat(result.document().content().phone()).isEmpty();
        assertThat(text(result.document())).contains("13800000000","support@example.invalid");
    }
    @Test void pdfLabeledContactInsideBodyStaysInBody() {
        var result=new DocxMapping().map(List.of(block("奶龙"),block("个人简介"),
            block("邮箱：service@example.invalid")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEmpty();
        assertThat(text(result.document())).contains("service@example.invalid");
    }
    @Test void pdfPhoneNumberLabelWorksInCombinedContactHeader() {
        var result=new DocxMapping().map(List.of(block("奶龙"),
            block("手机号：13800000000 | 邮箱：nailong@example.invalid")),List.of(),"PDF");
        assertThat(result.document().content().email()).isEqualTo("nailong@example.invalid");
        assertThat(result.document().content().phone()).isEqualTo("13800000000");
    }
    @Test void pdfPhoneNumberLabelWorksAsSingleField() {
        var result=new DocxMapping().map(List.of(block("奶龙"),block("手机号码：13800000000")),List.of(),"PDF");
        assertThat(result.document().content().phone()).isEqualTo("13800000000");
    }
}
