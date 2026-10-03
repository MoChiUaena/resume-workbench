package dev.localresume;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={"spring.datasource.hikari.schema=job_report_test","spring.flyway.schemas=job_report_test","spring.flyway.default-schema=job_report_test","resume.data-dir=./target/qa-data/job-report-http"})
@AutoConfigureMockMvc @Transactional
class JobReportHistoryTest {
    @Autowired MockMvc http;
    @Autowired ResumeService resumes;
    @Autowired ModelSettings settings;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TextSuggestions suggestions;
    @Autowired JobMatches matches;
    @MockitoBean ModelGateway gateway;
    ResumeService.Resume resume;
    JsonNode preview,report;
    String base;
    @BeforeEach void fixture()throws Exception {
        var sample=ResumeDocument.sample("one");
        var section=new ResumeDocument.Section("report-section","project","已选项目",true,false,List.of(new ResumeDocument.Entry("report-entry","","",true,List.of("Java persistence sample"))));
        var document=new ResumeDocument(ResumeDocument.SCHEMA_VERSION,new ResumeDocument.Content("LOCAL-PRIVATE-NAME","","private@example.invalid","PRIVATE-PHONE","",List.of(section)),sample.layout());
        resume=resumes.create("报告合成简历",document);base="/api/resumes/"+resume.id()+"/job-reports";
        var model=settings.view();if(model.profiles().isEmpty())model=settings.save(null,new ModelSettings.Edit(model.revision(),"报告测试模型","compatible","http://127.0.0.1:9999/v1","qa-report-model","REPORT-KEY-CANARY",false));
        if(!model.enabled())model=settings.enable(new ModelSettings.Enable(model.revision(),true));
        preview=json(post("/api/ai/job-matches/preview"),Map.of("resumeId",resume.id(),"expectedRevision",1,"sectionIds",List.of(section.id()),"jobDescription","需要 Java 开发","profileId",model.defaultId(),"settingsRevision",model.revision()),200);
        when(gateway.matchJob(any(),anyString(),anyString())).thenReturn("{\"items\":[{\"requirement\":\"Java\",\"status\":\"supported\",\"evidence\":[{\"sourceId\":\"s1\",\"quote\":\"Java\"}],\"advice\":\"\"}],\"suggestions\":[{\"sourceId\":\"s1\",\"replacement\":\"Java persistence sample.\"}]}");
        report=json(post("/api/ai/job-matches"),Map.of("previewId",preview.path("id").asText(),"confirmSend",true),200);
    }
    JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,Object body,int expected)throws Exception {
        if(body!=null)request.header("X-Local-Resume","1").contentType("application/json").content(mapper.writeValueAsString(body));
        var response=http.perform(request).andReturn().getResponse();assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(expected);return mapper.readTree(response.getContentAsByteArray());
    }
    Map<String,Object> saveBody(){return Map.of("previewId",preview.path("id").asText(),"reportId",report.path("id").asText(),"label","Java 岗位 · 第一次分析");}
    JsonNode save()throws Exception{return json(post(base),saveBody(),200);}

    @Test void savesServerSnapshotIdempotentlyAndReadsDurableHistoryWithoutModelOrResumeChanges()throws Exception {
        var saved=save();var again=save();assertThat(again.path("id").asText()).isEqualTo(saved.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_reports WHERE resume_id=?",Integer.class,resume.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT preview_id FROM job_reports WHERE id=?",UUID.class,UUID.fromString(saved.path("id").asText())))
            .isEqualTo(UUID.fromString(preview.path("id").asText()));
        var history=json(get(base),null,200);assertThat(history.path("total").asInt()).isEqualTo(1);assertThat(history.path("items").get(0).path("sourceRevision").asLong()).isEqualTo(1);
        var detail=json(get(base+"/"+saved.path("id").asText()),null,200);assertThat(detail.path("snapshot").path("sources").get(0).path("text").asText()).isEqualTo("Java persistence sample");
        assertThat(detail.toString()).doesNotContain("REPORT-KEY-CANARY","LOCAL-PRIVATE-NAME","PRIVATE-PHONE","expiresAt","selectionStart","encryptedKey","previewId","preview_id",preview.path("id").asText());
        assertThat(resumes.get(resume.id())).isEqualTo(resume);verify(gateway,times(1)).matchJob(any(),anyString(),anyString());
    }
    @Test void retainsAnOldAnalysisWhenResumeAndModelSettingsChange()throws Exception {
        var saved=save();var changed=resumes.save(resume.id(),new ResumeService.Save("新版",resume.document(),1,UUID.randomUUID()));
        var model=settings.view();settings.enable(new ModelSettings.Enable(model.revision(),false));
        var detail=json(get(base+"/"+saved.path("id").asText()),null,200);assertThat(detail.path("sourceChanged").asBoolean()).isTrue();assertThat(detail.path("snapshot").path("sourceRevision").asLong()).isEqualTo(1);
        assertThat(resumes.get(resume.id())).isEqualTo(changed);verify(gateway,times(1)).matchJob(any(),anyString(),anyString());
    }
    @Test void archiveOwnershipAndCachePairingAreEnforced()throws Exception {
        var other=resumes.create("另一份",ResumeDocument.sample("one"));var wrong="/api/resumes/"+other.id()+"/job-reports";
        json(post(wrong),saveBody(),422);var saved=save();json(get(wrong+"/"+saved.path("id").asText()),null,404);
        var mismatch=new HashMap<>(saveBody());mismatch.put("reportId",UUID.randomUUID().toString());json(post(base),mismatch,422);
        var renamed=new HashMap<>(saveBody());renamed.put("label","不同的保存请求");json(post(base),renamed,409);
    }
    @Test void savedReportRejectsAnotherPreviewEvenWhileGenerationCachesAreWarm()throws Exception {
        save();var model=settings.view();
        var otherPreview=matches.preview(new JobMatches.Request(resume.id(),1,List.of("report-section"),"另一个 Java 岗位",model.defaultId(),model.revision()));
        for(var wrongPreview:List.of(UUID.randomUUID(),otherPreview.id())) {
            var changed=new HashMap<>(saveBody());changed.put("previewId",wrongPreview);
            assertThat(json(post(base),changed,422).path("code").asText()).isEqualTo("JOB_REPORT_SOURCE_INVALID");
        }
        assertThat(save().path("id").asText()).isEqualTo(report.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_reports WHERE resume_id=?",Integer.class,resume.id())).isEqualTo(1);
    }
    @Test void deletionLeavesAnIdempotencyTombstoneAndCannotBeUndoneByAnOldSaveRetry()throws Exception {
        var saved=save();String path=base+"/"+saved.path("id").asText();
        json(delete(path),Map.of(),200);json(delete(path),Map.of(),200);json(get(path),null,404);json(post(base),saveBody(),410);
        var wrongPreview=new HashMap<>(saveBody());wrongPreview.put("previewId",UUID.randomUUID());
        assertThat(json(post(base),wrongPreview,410).path("code").asText()).isEqualTo("JOB_REPORT_DELETED");
        assertThat(json(get(base),null,200).path("total").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT snapshot IS NULL AND deleted_at IS NOT NULL FROM job_reports WHERE id=?",Boolean.class,UUID.fromString(saved.path("id").asText()))).isTrue();
        assertThat(resumes.get(resume.id())).isEqualTo(resume);
    }
    @Test void deletingAResumeRemovesItsArchiveAndItsTombstones()throws Exception {
        save();resumes.delete(resume.id(),1);assertThat(jdbc.queryForObject("SELECT count(*) FROM job_reports WHERE resume_id=?",Integer.class,resume.id())).isZero();json(get(base),null,404);
    }
    @Test void durableReadsAndLostSaveReceiptsSurviveEmptyGenerationCaches()throws Exception {
        var saved=save();var coldMatches=new JobMatches(resumes,settings,gateway,suggestions,mapper);
        var coldHistory=new JobReportHistory(jdbc,mapper,coldMatches);
        var id=UUID.fromString(saved.path("id").asText());
        assertThat(coldHistory.get(resume.id(),id).snapshot().jobDescription()).isEqualTo("需要 Java 开发");
        var receipt=coldHistory.save(resume.id(),new JobReportHistory.Save(UUID.fromString(preview.path("id").asText()),id,"Java 岗位 · 第一次分析"));
        assertThat(receipt.id()).isEqualTo(id);assertThat(coldHistory.history(resume.id(),0).total()).isEqualTo(1);
        assertThatThrownBy(()->coldHistory.save(resume.id(),new JobReportHistory.Save(UUID.randomUUID(),id,"Java 岗位 · 第一次分析")))
            .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code).isEqualTo("JOB_REPORT_SOURCE_INVALID");assertThat(e.status).isEqualTo(422);});
        assertThatThrownBy(()->coldHistory.save(resume.id(),new JobReportHistory.Save(UUID.fromString(preview.path("id").asText()),id,"另一名称")))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("REPORT_SAVE_CONFLICT"));
        assertThatThrownBy(()->coldHistory.save(resume.id(),new JobReportHistory.Save(UUID.randomUUID(),UUID.randomUUID(),"未保存且缓存消失")))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("JOB_REPORT_EXPIRED"));
        verify(gateway,times(1)).matchJob(any(),anyString(),anyString());
    }
    @Test void unreadableStoredSnapshotIsRejectedWithoutDeletingTheOriginalRecord()throws Exception {
        var saved=save();var id=UUID.fromString(saved.path("id").asText());
        jdbc.update("UPDATE job_reports SET snapshot=snapshot||'{\"sendToken\":\"unexpected-live-token\"}'::jsonb WHERE id=?",id);
        assertThat(json(get(base+"/"+id),null,503).path("code").asText()).isEqualTo("JOB_REPORT_UNREADABLE");
        assertThat(jdbc.queryForObject("SELECT snapshot->>'sendToken' FROM job_reports WHERE id=?",String.class,id)).isEqualTo("unexpected-live-token");
        assertThat(resumes.get(resume.id())).isEqualTo(resume);
    }
    @ParameterizedTest(name="malformed JSONB snapshot: {0}")
    @ValueSource(strings={"nullParagraph","missingParagraph","fractionalParagraph","textualParagraph","numericProfile","booleanAdvice"})
    void malformedDatabaseSnapshotTypesAreUnreadableWithoutChangingTheStoredRecord(String mode)throws Exception {
        var saved=save();var id=UUID.fromString(saved.path("id").asText());
        var payload=(com.fasterxml.jackson.databind.node.ObjectNode)saved.path("snapshot").deepCopy();
        var source=(com.fasterxml.jackson.databind.node.ObjectNode)payload.path("sources").get(0);
        switch(mode) {
            case "nullParagraph"->source.putNull("paragraph");
            case "missingParagraph"->source.remove("paragraph");
            case "fractionalParagraph"->source.put("paragraph",0.9);
            case "textualParagraph"->source.put("paragraph","0");
            case "numericProfile"->payload.put("profileName",123);
            case "booleanAdvice"->((com.fasterxml.jackson.databind.node.ObjectNode)payload.path("items").get(0)).put("advice",false);
        }
        jdbc.update("UPDATE job_reports SET snapshot=?::jsonb WHERE id=?",mapper.writeValueAsString(payload),id);
        assertThat(json(get(base+"/"+id),null,503).path("code").asText()).isEqualTo("JOB_REPORT_UNREADABLE");
        assertThat(mapper.readTree(jdbc.queryForObject("SELECT snapshot::text FROM job_reports WHERE id=?",String.class,id))).isEqualTo(payload);
        assertThat(resumes.get(resume.id())).isEqualTo(resume);
    }
    @Test void historyIsStablyPagedAndTheLimitCountsOnlyActiveReports()throws Exception {
        var saved=save();var savedId=UUID.fromString(saved.path("id").asText());
        for(int n=0;n<99;n++)jdbc.update("INSERT INTO job_reports(id,resume_id,preview_id,label,source_revision,snapshot,created_at) SELECT ?,resume_id,?,?,source_revision,snapshot,created_at FROM job_reports WHERE id=?",UUID.randomUUID(),UUID.randomUUID(),"fixture "+n,savedId);
        var all=new LinkedHashSet<String>();
        for(int page=0;page<5;page++) {
            var first=json(get(base).param("page",String.valueOf(page)),null,200);
            assertThat(first.path("items").size()).isEqualTo(20);assertThat(first.path("total").asInt()).isEqualTo(100);
            assertThat(json(get(base).param("page",String.valueOf(page)),null,200)).isEqualTo(first);
            first.path("items").forEach(row->assertThat(all.add(row.path("id").asText())).isTrue());
        }
        assertThat(all).hasSize(100);assertThat(json(get(base).param("page","5"),null,200).path("items").size()).isZero();
        var model=settings.view();var next=matches.preview(new JobMatches.Request(resume.id(),1,List.of("report-section"),"需要 Java 开发",model.defaultId(),model.revision()));
        var nextReport=matches.generate(new JobMatches.Generate(next.id(),true));
        var nextBody=Map.of("previewId",next.id(),"reportId",nextReport.id(),"label","额度内新报告");
        assertThat(json(post(base),nextBody,422).path("code").asText()).isEqualTo("JOB_REPORT_LIMIT");
        json(delete(base+"/"+savedId),Map.of(),200);json(post(base),nextBody,200);
        assertThat(json(get(base),null,200).path("total").asInt()).isEqualTo(100);assertThat(resumes.get(resume.id())).isEqualTo(resume);
    }
}
