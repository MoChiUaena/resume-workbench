package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
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
    @Test void selectionSendsOnlySavedSliceAndAppliesInsideOriginalParagraph(){
        var base=request(true);String paragraph=ResumeService.paragraph(source.document(),base.sectionId(),base.entryId(),0);
        int start=3,end=16;String selected=paragraph.substring(start,end),replacement="简洁表达";
        when(gateway.rewrite(any(),any(),eq(selected))).thenReturn(replacement);
        var input=new TextSuggestions.Request(source.id(),1,base.sectionId(),base.entryId(),0,start,end,models.defaultId(),models.revision(),true);
        var suggestion=service.create(input);
        assertThat(suggestion.original()).isEqualTo(paragraph);
        assertThat(suggestion.selectedOriginal()).isEqualTo(selected);
        assertThat(suggestion.selectionStart()).isEqualTo(start);
        assertThat(suggestion.selectionEnd()).isEqualTo(end);
        verify(gateway).rewrite(any(),eq("secret-canary"),eq(selected));
        service.apply(suggestion.id(),source.id(),1,true);
        verify(resumes).applyParagraph(eq(source.id()),eq(1L),eq(base.sectionId()),eq(base.entryId()),eq(0),eq(paragraph),
            eq(paragraph.substring(0,start)+replacement+paragraph.substring(end)),eq(suggestion.id()));
    }
    @Test void invalidAndPartialRangesAreRejectedBeforeCallingAProvider(){
        var base=request(true);String paragraph=ResumeService.paragraph(source.document(),base.sectionId(),base.entryId(),0);
        for(var pair:java.util.List.of(new Integer[]{0,0},new Integer[]{1,paragraph.length()+1},new Integer[]{1,null})){
            var input=new TextSuggestions.Request(source.id(),1,base.sectionId(),base.entryId(),0,pair[0],pair[1],models.defaultId(),models.revision(),true);
            assertThatThrownBy(()->service.create(input)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_SELECTION_INVALID"));
        }
        verifyNoInteractions(gateway);
    }
    @Test void applyingSelectionCannotOverrunParagraphLimit(){
        var base=request(true);String paragraph=ResumeService.paragraph(source.document(),base.sectionId(),base.entryId(),0);
        when(gateway.rewrite(any(),any(),any())).thenReturn("文".repeat(800));
        var input=new TextSuggestions.Request(source.id(),1,base.sectionId(),base.entryId(),0,0,1,models.defaultId(),models.revision(),true);
        var suggestion=service.create(input);
        assertThatThrownBy(()->service.apply(suggestion.id(),source.id(),1,true))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_RESULT_TOO_LONG"));
        verify(resumes,never()).applyParagraph(any(),anyLong(),any(),any(),anyInt(),any(),any(),any());
        assertThat(paragraph).isNotBlank();
    }
    @Test void selectedOffsetsCannotSplitAnEmojiSurrogatePair(){
        var doc=source.document();var content=doc.content();var sections=new ArrayList<>(content.sections());
        var section=sections.getFirst();var entries=new ArrayList<>(section.entries());var entry=entries.getFirst();
        entries.set(0,new ResumeDocument.Entry(entry.id(),entry.title(),entry.meta(),entry.bulleted(),List.of("🚀参与联调")));
        sections.set(0,new ResumeDocument.Section(section.id(),section.type(),section.title(),section.visible(),section.pageBreakBefore(),entries));
        var changed=new ResumeDocument(doc.schemaVersion(),new ResumeDocument.Content(content.name(),content.headline(),content.email(),content.phone(),content.location(),sections),doc.layout());
        source=new ResumeService.Resume(source.id(),source.title(),changed,1,source.lastMutationId(),source.updatedAt());
        when(resumes.savedRevision(source.id(),1)).thenReturn(source);
        var base=request(true);
        var split=new TextSuggestions.Request(source.id(),1,base.sectionId(),base.entryId(),0,1,3,models.defaultId(),models.revision(),true);
        assertThatThrownBy(()->service.create(split)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_SELECTION_INVALID"));
        var valid=new TextSuggestions.Request(source.id(),1,base.sectionId(),base.entryId(),0,2,4,models.defaultId(),models.revision(),true);
        assertThat(service.create(valid).selectedOriginal()).isEqualTo("参与");
        verify(gateway).rewrite(any(),eq("secret-canary"),eq("参与"));
    }
    @Test void manuallyReviewedSelectionUsesExactlyOneLockedPayloadForRetries(){
        var base=request(true);String paragraph=ResumeService.paragraph(source.document(),base.sectionId(),base.entryId(),0);
        var input=new TextSuggestions.Request(source.id(),1,base.sectionId(),base.entryId(),0,3,16,models.defaultId(),models.revision(),true);
        var suggestion=service.create(input);String reviewed="人工核对后的表达";
        service.apply(suggestion.id(),source.id(),1,true,reviewed);
        String expected=paragraph.substring(0,3)+reviewed+paragraph.substring(16);
        verify(resumes).applyParagraph(eq(source.id()),eq(1L),eq(base.sectionId()),eq(base.entryId()),eq(0),eq(paragraph),eq(expected),eq(suggestion.id()));
        service.apply(suggestion.id(),source.id(),1,true,reviewed);
        verify(resumes,times(2)).applyParagraph(eq(source.id()),eq(1L),any(),any(),anyInt(),any(),eq(expected),eq(suggestion.id()));
        assertThatThrownBy(()->service.apply(suggestion.id(),source.id(),1,true,"另一份文字"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_APPLY_TEXT_CHANGED"));
        verify(gateway,times(1)).rewrite(any(),any(),any());
        verifyNoMoreInteractions(gateway);
    }
    @Test void invalidOrFailedManualReviewDoesNotLockTheSuggestion(){
        var suggestion=service.create(request(true));
        for(String invalid:List.of(" ","两段\n文字","文".repeat(801)))
            assertThatThrownBy(()->service.apply(suggestion.id(),source.id(),1,true,invalid))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AI_REVIEW_TEXT_INVALID"));
        verify(resumes,never()).applyParagraph(any(),anyLong(),any(),any(),anyInt(),any(),any(),any());
        when(resumes.applyParagraph(any(),anyLong(),any(),any(),anyInt(),any(),any(),any()))
            .thenThrow(new ApiException("REVISION_CONFLICT","测试中的回滚",409)).thenReturn(source);
        assertThatThrownBy(()->service.apply(suggestion.id(),source.id(),1,true,"第一版校正"))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("REVISION_CONFLICT"));
        assertThat(service.apply(suggestion.id(),source.id(),1,true,"第二版校正")).isEqualTo(source);
    }
}
