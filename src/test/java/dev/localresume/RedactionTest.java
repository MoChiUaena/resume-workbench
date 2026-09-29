package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RedactionTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private ResumeDocument document() {
        var sample=ResumeDocument.sample("one");var layout=sample.layout();
        var content=new ResumeDocument.Content("奶龙","奶龙的 Java 后端方向","nailong@example.invalid","138 0000 0000","奶龙城市",
            List.of(new ResumeDocument.Section("section","project","奶龙项目",true,false,List.of(new ResumeDocument.Entry("entry",
                "奶龙 · 项目","联系 nailong@example.invalid",true,List.of("奶龙城市 · 奶龙 · 138 0000 0000","其他资料：other@example.invalid"))))));
        var slot=new ResumeDraft.ImageSlot(UUID.randomUUID().toString(),true,26,34,"cover",1,1.5,25,75);
        return new ResumeDocument(4,content,new ResumeDocument.Layout("rail","serif",12,1.8,7,16,true,slot,slot,layout.presentation()));
    }
    @Test void defaultsRemoveKnownIdentifiersAcrossAllContentAndRemoveImageReferencesWithoutMutatingSource()throws Exception {
        var original=document();String before=mapper.writeValueAsString(original);
        var copy=Redaction.apply(original,Redaction.Options.defaults());
        assertThat(mapper.writeValueAsString(copy)).doesNotContain("奶龙","nailong@example.invalid","138 0000 0000",original.layout().photo().id());
        assertThat(copy.content().name()).isEqualTo("候选人");assertThat(copy.content().phone()).isEmpty();
        assertThat(copy.content().sections().getFirst().entries().getFirst().bullets().getFirst()).isEqualTo("（位置已隐藏） · 候选人 · （电话已隐藏）");
        assertThat(copy.content().sections().getFirst().entries().getFirst().bullets().get(1)).contains("other@example.invalid");
        assertThat(copy.layout().photo().id()).isNull();assertThat(copy.layout().photo().visible()).isFalse();
        assertThat(copy.layout().logo().id()).isNull();assertThat(copy.layout().photo().positionX()).isEqualTo(25);
        assertThat(copy.layout().template()).isEqualTo(original.layout().template());assertThat(copy.layout().presentation()).isEqualTo(original.layout().presentation());
        assertThat(mapper.writeValueAsString(original)).isEqualTo(before);
    }
    @Test void choicesAreIndependentAndBodyCanBePreserved() {
        var original=document();var copy=Redaction.apply(original,new Redaction.Options(false,true,false,false,false,true,false));
        assertThat(copy.content().name()).isEqualTo(original.content().name());assertThat(copy.content().phone()).isEmpty();
        assertThat(copy.content().email()).isEqualTo(original.content().email());assertThat(copy.content().sections()).isEqualTo(original.content().sections());
        assertThat(copy.content().headline()).isEqualTo(original.content().headline());assertThat(copy.layout().photo()).isEqualTo(original.layout().photo());
        assertThat(copy.layout().logo().id()).isNull();
        assertThat(Redaction.apply(original,new Redaction.Options(false,false,false,false,false,false,true))).isEqualTo(original);
    }
    @Test void matchingIsLiteralAndReplacementTextIsNotProcessedAgain() {
        var sample=ResumeDocument.sample("one");
        var content=new ResumeDocument.Content("A.*$","A.*$ / 候选人","","候选人","",List.of());
        var copy=Redaction.apply(new ResumeDocument(4,content,sample.layout()),Redaction.Options.defaults());
        assertThat(copy.content().headline()).isEqualTo("候选人 / （电话已隐藏）");
    }
    @Test void englishPresentationUsesEnglishPlaceholderAndEveryChoiceMustBeExplicit() {
        var original=document();var layout=original.layout();var presentation=layout.presentation();
        var english=new ResumeDocument.Presentation("en",presentation.accentColor(),presentation.alignment(),presentation.contactStyle(),presentation.headingStyle(),
            presentation.marginHorizontalMm(),presentation.marginTopMm(),presentation.marginBottomMm(),presentation.entryGapMm(),presentation.paragraphGapMm());
        var doc=new ResumeDocument(4,original.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),
            layout.swapImages(),layout.photo(),layout.logo(),english));
        assertThat(Redaction.apply(doc,Redaction.Options.defaults()).content().name()).isEqualTo("Candidate");
        try(var factory=Validation.buildDefaultValidatorFactory()){
            assertThat(factory.getValidator().validate(new Redaction.Options(true,null,true,true,true,true,true))).isNotEmpty();
            assertThat(factory.getValidator().validate(Redaction.Options.defaults())).isEmpty();
        }
    }
}
