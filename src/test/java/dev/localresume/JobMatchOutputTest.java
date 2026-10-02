package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JobMatchOutputTest {
    final ObjectMapper mapper=new ObjectMapper();
    final JobMatches.Preview preview=new JobMatches.Preview(UUID.randomUUID(),UUID.randomUUID(),1,1,"profile",
        "fixture","compatible","fixture-model","http://127.0.0.1/v1/chat/completions","需要 Java 开发",
        "fixture payload",List.of(
            new JobMatches.Source("s1","section","entry",-1,"项目","标题","只读标题"),
            new JobMatches.Source("s2","section","entry",0,"项目","标题","参与 Java 开发")),Instant.parse("2026-10-02T00:00:00Z"));
    String item(String requirement,String status,String evidence){return "{\"items\":[{\"requirement\":\""+requirement+"\",\"status\":\""+status+"\",\"evidence\":"+evidence+",\"advice\":\"人工核对\"}],\"suggestions\":[]}";}
    String quote(String id,String value){return "[{\"sourceId\":\""+id+"\",\"quote\":\""+value+"\"}]";}
    void invalid(String raw){assertThatThrownBy(()->JobMatchOutput.decode(mapper,raw,preview))
        .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("JOB_MATCH_OUTPUT_INVALID"));}
    @Test void acceptsExactRequirementsQuotesAndReadonlyHeadingEvidence(){
        var result=JobMatchOutput.decode(mapper,item("需要 Java 开发","partial",quote("s1","只读标题")),preview);
        assertThat(result.items().getFirst().evidence().getFirst().sourceId()).isEqualTo("s1");
        assertThat(result.suggestions()).isEmpty();
    }
    @Test void rejectsWhitespaceRequirementAndUnsupportedEvidence(){
        invalid(item(" ","supported",quote("s2","Java")));
        invalid(item("不存在的岗位要求","supported",quote("s2","Java")));
        invalid(item("需要 Java 开发","supported","[]"));
        invalid(item("需要 Java 开发","missing",quote("s2","Java")));
    }
    @Test void rejectsInventedSourcesQuotesAndTitleRewrites(){
        invalid(item("需要 Java 开发","supported",quote("forged","Java")));
        invalid(item("需要 Java 开发","supported",quote("s2","伪造引用")));
        invalid(item("需要 Java 开发","supported",quote("s2"," ")));
        invalid("{\"items\":[{\"requirement\":\"需要 Java 开发\",\"status\":\"supported\",\"evidence\":[{\"sourceId\":\"s2\",\"quote\":\"Java\"}],\"advice\":\"人工核对\"}],\"suggestions\":[{\"sourceId\":\"s1\",\"replacement\":\"改标题\"}]}");
    }
    @Test void rejectsExtraFieldsBadJsonDuplicateKeysAndExcessSuggestions(){
        invalid("not json");
        invalid(item("需要 Java 开发","missing","[]").replace("\"suggestions\":[]","\"suggestions\":[],\"extra\":1"));
        invalid(item("需要 Java 开发","missing","[]").replace("\"status\":\"missing\"","\"status\":\"missing\",\"status\":\"missing\""));
        String candidate="{\"sourceId\":\"s2\",\"replacement\":\"清晰表达\"}";
        invalid(item("需要 Java 开发","missing","[]").replace("\"suggestions\":[]","\"suggestions\":["+String.join(",",Collections.nCopies(7,candidate))+"]"));
    }
}
