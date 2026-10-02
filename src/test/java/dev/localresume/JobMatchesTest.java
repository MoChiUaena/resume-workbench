package dev.localresume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties={"spring.datasource.hikari.schema=resume_test", "spring.flyway.schemas=resume_test", "spring.flyway.default-schema=resume_test", "resume.data-dir=./target/qa-data/jobmatch-http"})
@AutoConfigureMockMvc @Transactional
class JobMatchesTest {
    @Autowired MockMvc http;
    @Autowired ResumeService resumes;
    @Autowired ModelSettings settings;
    @Autowired ObjectMapper mapper;
    @MockitoBean ModelGateway gateway;
    ResumeService.Resume resume;
    ModelSettings.View model;
    String selectedSection;

    @BeforeEach void fixture(){
        var sample=ResumeDocument.sample("one");var first=sample.content().sections().getFirst();var other=sample.content().sections().get(1);
        var selected=new ResumeDocument.Section(first.id(),first.type(),"已选模块",true,false,
            List.of(new ResumeDocument.Entry(first.entries().getFirst().id(),"条目标题","",true,List.of("SELECTED-JAVA-SOURCE"))));
        var excluded=new ResumeDocument.Section(other.id(),other.type(),"未选模块",true,false,
            List.of(new ResumeDocument.Entry(other.entries().getFirst().id(),"另一个条目","",true,List.of("OTHER-PRIVATE-SOURCE"))));
        var content=new ResumeDocument.Content("PRIVATE-NAME","", "PRIVATE-EMAIL", "PRIVATE-PHONE","PRIVATE-LOCATION",List.of(selected,excluded));
        resume=resumes.create("fixture",new ResumeDocument(ResumeDocument.SCHEMA_VERSION,content,sample.layout()));
        selectedSection=selected.id();
        var view=settings.view();
        if(view.profiles().isEmpty())view=settings.save(null,new ModelSettings.Edit(view.revision(),"测试模型","compatible","http://127.0.0.1:9999/v1","qa-model","fixture-key",false));
        model=view.enabled()?view:settings.enable(new ModelSettings.Enable(view.revision(),true));
    }
    JsonNode send(String path,Object body)throws Exception{
        var response=http.perform(post(path).header("X-Local-Resume","1").contentType("application/json")
            .content(body instanceof String text?text:mapper.writeValueAsString(body))).andReturn().getResponse();
        return mapper.readTree(response.getContentAsByteArray());
    }
    int status(String path,Object body)throws Exception{return http.perform(post(path).header("X-Local-Resume","1").contentType("application/json")
        .content(body instanceof String text?text:mapper.writeValueAsString(body))).andReturn().getResponse().getStatus();}
    Map<String,Object> previewRequest(){return Map.of("resumeId",resume.id(),"expectedRevision",1,"sectionIds",List.of(selectedSection),
        "jobDescription","需要 Java 开发","profileId",model.defaultId(),"settingsRevision",model.revision());}
    JsonNode preview()throws Exception{return send("/api/ai/job-matches/preview",previewRequest());}
    Map<String,Object> generation(JsonNode p,boolean confirm){return Map.of("previewId",p.path("id").asText(),"confirmSend",confirm);}

    @Test void previewContainsOnlySelectedSourceAndNeverCallsModel()throws Exception{
        var p=preview();assertThat(p.path("id").asText()).isNotBlank();
        assertThat(p.path("payload").asText()).contains("SELECTED-JAVA-SOURCE","需要 Java 开发")
            .doesNotContain("PRIVATE-NAME","PRIVATE-EMAIL","PRIVATE-PHONE","PRIVATE-LOCATION","OTHER-PRIVATE-SOURCE","layout","photo","logo");
        assertThat(p.path("sources")).hasSize(2);
        assertThat(p.path("sources").get(0).path("paragraph").asInt()).isEqualTo(-1);
        assertThat(p.path("sources").get(1).path("paragraph").asInt()).isZero();
        verifyNoInteractions(gateway);
    }
    @Test void confirmationAndSettingsOrResumeChangesPreventSending()throws Exception{
        var p=preview();assertThat(status("/api/ai/job-matches",generation(p,false))).isEqualTo(422);
        verifyNoInteractions(gateway);
        settings.enable(new ModelSettings.Enable(model.revision(),false));
        assertThat(status("/api/ai/job-matches",generation(p,true))).isEqualTo(409);
        verifyNoInteractions(gateway);
        model=settings.enable(new ModelSettings.Enable(settings.view().revision(),true));
        var fresh=preview();
        resumes.save(resume.id(),new ResumeService.Save("changed",resume.document(),1,UUID.randomUUID()));
        assertThat(status("/api/ai/job-matches",generation(fresh,true))).isEqualTo(409);
        verifyNoInteractions(gateway);
    }
    @Test void boundsAndInvalidSelectionAreRejectedBeforePreview()throws Exception{
        var request=new HashMap<>(previewRequest());request.put("jobDescription"," ");
        assertThat(status("/api/ai/job-matches/preview",request)).isEqualTo(422);
        request.put("jobDescription","J".repeat(6001));assertThat(status("/api/ai/job-matches/preview",request)).isEqualTo(422);
        request.put("jobDescription","Java");request.put("sectionIds",List.of("missing"));
        assertThat(status("/api/ai/job-matches/preview",request)).isEqualTo(422);
        verifyNoInteractions(gateway);
    }
    @Test void malformedRequestJsonIsRejectedWithoutProviderCall()throws Exception{
        assertThat(status("/api/ai/job-matches/preview","{bad json")).isEqualTo(400);
        verifyNoInteractions(gateway);
    }
    @Test void sourceChangedDuringProviderReplyRejectsReportAndSuggestions()throws Exception{
        var p=preview();
        when(gateway.matchJob(any(),any(),any())).thenAnswer(call->{
            resumes.save(resume.id(),new ResumeService.Save("changed",resume.document(),1,UUID.randomUUID()));
            return "{\"items\":[{\"requirement\":\"需要 Java 开发\",\"status\":\"missing\",\"evidence\":[],\"advice\":\"人工核对\"}],\"suggestions\":[]}";
        });
        assertThat(status("/api/ai/job-matches",generation(p,true))).isEqualTo(409);
        assertThat(resumes.get(resume.id()).revision()).isEqualTo(2);
    }
    @Test void invalidProviderReferencesLeaveResumeAndHistoryUntouched()throws Exception{
        var p=preview();int versions=resumes.versions(resume.id()).size();
        when(gateway.matchJob(any(),any(),any())).thenReturn("{\"items\":[{\"requirement\":\"需要 Java 开发\",\"status\":\"supported\",\"evidence\":[{\"sourceId\":\"forged\",\"quote\":\"Java\"}],\"advice\":\"核对\"}],\"suggestions\":[]}");
        assertThat(status("/api/ai/job-matches",generation(p,true))).isEqualTo(422);
        assertThat(resumes.get(resume.id()).revision()).isEqualTo(1);
        assertThat(resumes.versions(resume.id())).hasSize(versions);
    }
    @Test void validReportIsReadOnlyAndSuggestionUsesExistingApplySnapshotAndRetry()throws Exception{
        var p=preview();
        when(gateway.matchJob(any(),any(),any())).thenReturn("{\"items\":[{\"requirement\":\"需要 Java 开发\",\"status\":\"supported\",\"evidence\":[{\"sourceId\":\"s2\",\"quote\":\"SELECTED-JAVA-SOURCE\"}],\"advice\":\"请核对原经历\"}],\"suggestions\":[{\"sourceId\":\"s2\",\"replacement\":\"REVIEWED-JAVA-SOURCE\"}]}");
        int before=resumes.versions(resume.id()).size();var report=send("/api/ai/job-matches",generation(p,true));
        assertThat(send("/api/ai/job-matches",generation(p,true)).path("id").asText()).isEqualTo(report.path("id").asText());
        assertThat(report.path("items").get(0).path("status").asText()).isEqualTo("supported");
        assertThat(report.path("suggestions")).hasSize(1);
        assertThat(resumes.get(resume.id()).revision()).isEqualTo(1);
        assertThat(resumes.versions(resume.id())).hasSize(before);
        String suggestionId=report.path("suggestions").get(0).path("id").asText();
        var apply=Map.of("resumeId",resume.id(),"expectedRevision",1,"confirmApply",true,"reviewedText","HUMAN-REVIEWED-SOURCE");
        var applied=send("/api/ai/suggestions/"+suggestionId+"/apply",apply);
        assertThat(applied.path("revision").asLong()).isEqualTo(2);
        assertThat(resumes.get(resume.id()).document().content().sections().getFirst().entries().getFirst().bullets().getFirst()).isEqualTo("HUMAN-REVIEWED-SOURCE");
        assertThat(resumes.get(resume.id()).document().content().name()).isEqualTo("PRIVATE-NAME");
        assertThat(resumes.get(resume.id()).document().layout()).isEqualTo(resume.document().layout());
        assertThat(resumes.versions(resume.id())).hasSize(before+1);
        assertThat(send("/api/ai/suggestions/"+suggestionId+"/apply",apply).path("revision").asLong()).isEqualTo(2);
        assertThat(resumes.versions(resume.id())).hasSize(before+1);
        verify(gateway,times(1)).matchJob(any(),eq("fixture-key"),eq(p.path("payload").asText()));
        verifyNoMoreInteractions(gateway);
    }
}
