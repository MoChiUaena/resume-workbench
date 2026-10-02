package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobMatchesExpiryTest {
    @TempDir Path data;
    Instant now=Instant.parse("2026-10-02T00:00:00Z");
    Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}};
    record Fixture(JobMatches matches,ModelGateway gateway,TextSuggestions suggestions,ResumeService.Resume source,ModelSettings.View view) {}
    Fixture fixture(){
        var mapper=new ObjectMapper().findAndRegisterModules();
        var settings=new ModelSettings(mapper,data.toString());
        var view=settings.save(null,new ModelSettings.Edit(0,"fixture","compatible","http://127.0.0.1:9999/v1","qa-model","fixture-key",false));
        view=settings.enable(new ModelSettings.Enable(view.revision(),true));
        var resumes=mock(ResumeService.class);var gateway=mock(ModelGateway.class);var suggestions=mock(TextSuggestions.class);
        var source=new ResumeService.Resume(UUID.randomUUID(),"fixture",ResumeDocument.sample("one"),1,null,now);
        when(resumes.savedRevision(source.id(),1)).thenReturn(source);
        var matches=new JobMatches(resumes,settings,gateway,suggestions,mapper,clock);
        return new Fixture(matches,gateway,suggestions,source,view);
    }
    JobMatches.Preview preview(Fixture f){return f.matches().preview(new JobMatches.Request(f.source().id(),1,
        List.of(f.source().document().content().sections().getFirst().id()),"需要 Java 开发",f.view().defaultId(),f.view().revision()));}
    static final String MISSING="{\"items\":[{\"requirement\":\"需要 Java 开发\",\"status\":\"missing\",\"evidence\":[],\"advice\":\"人工核对\"}],\"suggestions\":[]}";
    @Test void replyArrivingAfterPreviewExpiryCannotCreateAReport(){
        var f=fixture();var p=preview(f);
        when(f.gateway().matchJob(any(),any(),any())).thenAnswer(call->{now=now.plusSeconds(901);return MISSING;});
        assertThatThrownBy(()->f.matches().generate(new JobMatches.Generate(p.id(),true)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("JOB_MATCH_PREVIEW_EXPIRED"));
        verifyNoInteractions(f.suggestions());
    }
    @Test void previewCacheBoundsHowManyConfirmationsRemainUsable(){
        var f=fixture();var ids=new ArrayList<UUID>();
        for(int n=0;n<33;n++)ids.add(preview(f).id());
        when(f.gateway().matchJob(any(),any(),any())).thenReturn(MISSING);
        int expired=0,created=0;
        for(UUID id:ids)try{f.matches().generate(new JobMatches.Generate(id,true));created++;}
            catch(ApiException e){assertThat(e.code).isEqualTo("JOB_MATCH_PREVIEW_EXPIRED");expired++;}
        assertThat(expired).isEqualTo(1);assertThat(created).isEqualTo(32);
        verify(f.gateway(),times(32)).matchJob(any(),any(),any());
    }
    @Test void thirdConcurrentGenerationIsRejectedWhileTwoCallsAreInFlight()throws Exception{
        var f=fixture();var a=preview(f);var b=preview(f);var c=preview(f);
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        when(f.gateway().matchJob(any(),any(),any())).thenAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");return MISSING;});
        var workers=Executors.newFixedThreadPool(2);
        try{
            var first=workers.submit(()->f.matches().generate(new JobMatches.Generate(a.id(),true)));
            var second=workers.submit(()->f.matches().generate(new JobMatches.Generate(b.id(),true)));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->f.matches().generate(new JobMatches.Generate(c.id(),true)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("MODEL_BUSY"));
            release.countDown();assertThat(first.get(10,TimeUnit.SECONDS).items()).hasSize(1);
            assertThat(second.get(10,TimeUnit.SECONDS).items()).hasSize(1);
            verify(f.gateway(),times(2)).matchJob(any(),any(),any());
        }finally{release.countDown();workers.shutdownNow();}
    }
}
