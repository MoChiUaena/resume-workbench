package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextSuggestionsTest {
    @TempDir Path temp;
    ResumeService resumes=mock(ResumeService.class);ModelGateway gateway=mock(ModelGateway.class);
    ModelSettings settings;ResumeService.Resume source;ModelSettings.View models;TextSuggestions service;
    Instant now=Instant.parse("2026-09-29T00:00:00Z");
    Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}};
    @BeforeEach void setup(){
        settings=new ModelSettings(new ObjectMapper().findAndRegisterModules(),temp.toString());
        var view=settings.save(null,new ModelSettings.Edit(0,"测试服务","compatible","http://127.0.0.1:9999/v1","qa-model","secret-canary",false));models=settings.enable(new ModelSettings.Enable(view.revision(),true));
        source=new ResumeService.Resume(UUID.randomUUID(),"私有简历标题",ResumeDocument.sample("one"),1,null,now);
        when(resumes.savedRevision(source.id(),1)).thenReturn(source);when(gateway.rewrite(any(),any(),any())).thenReturn("测试改写，没有新增事实。");
        service=new TextSuggestions(resumes,settings,gateway,clock);
    }
    TextSuggestions.Request request(boolean consent){var section=source.document().content().sections().getFirst();var entry=section.entries().getFirst();return new TextSuggestions.Request(source.id(),1,section.id(),entry.id(),0,models.defaultId(),models.revision(),consent);}
    @Test void generationSendsOnlyOneSavedParagraphAndDoesNotWriteOrCreateVersion(){
        var input=request(true);String text=ResumeService.paragraph(source.document(),input.sectionId(),input.entryId(),0);var result=service.create(input);
        verify(gateway).rewrite(any(),eq("secret-canary"),eq(text));verify(resumes,never()).applyParagraph(any(),anyLong(),any(),any(),anyInt(),any(),any(),any());verify(resumes,never()).checkpoint(any(),anyLong(),any());
        assertThat(result.original()).isEqualTo(text);assertThat(result.destination()).isEqualTo("http://127.0.0.1:9999/v1/chat/completions");assertThat(result.replacement()).isEqualTo("测试改写，没有新增事实。");
    }
    @Test void explicitConsentAndEnabledSettingsAreCheckedBeforeCallingTheProvider(){
        assertThatThrownBy(()->service.create(request(false))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_SEND_CONFIRMATION_REQUIRED"));
        models=settings.enable(new ModelSettings.Enable(models.revision(),false));assertThatThrownBy(()->service.create(request(true))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_DISABLED"));verifyNoInteractions(gateway);
    }
    @Test void expiryWrongResumeAndUnconfirmedApplyCannotSaveAnything(){
        var result=service.create(request(true));
        assertThatThrownBy(()->service.apply(result.id(),source.id(),1,false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.apply(result.id(),UUID.randomUUID(),1,true)).isInstanceOf(ApiException.class);
        now=now.plusSeconds(601);assertThatThrownBy(()->service.apply(result.id(),source.id(),1,true)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_SUGGESTION_EXPIRED"));
        verify(resumes,never()).applyParagraph(any(),anyLong(),any(),any(),anyInt(),any(),any(),any());
    }
    @Test void changingConfigurationDuringGenerationInvalidatesTheReply(){
        when(gateway.rewrite(any(),any(),any())).thenAnswer(call->{settings.enable(new ModelSettings.Enable(models.revision(),false));return "测试建议";});
        assertThatThrownBy(()->service.create(request(true))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("MODEL_SETTINGS_CHANGED"));
    }
    @Test void newNumericClaimsAreFlaggedForReview(){assertThat(TextSuggestions.addsNumbers("完成接口改造","完成接口改造，提升 30% 性能")).isTrue();assertThat(TextSuggestions.addsNumbers("处理 30% 请求","处理 30% 请求并说明设计")).isFalse();}
}
