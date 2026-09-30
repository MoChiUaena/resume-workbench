package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class ModelGatewayTest {
    HttpServer server;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final AtomicReference<String> request=new AtomicReference<>(),auth=new AtomicReference<>(),path=new AtomicReference<>();
    final AtomicInteger calls=new AtomicInteger();
    String content="{\"text\":\"实现清晰的事务处理与版本恢复。\"}";int status=200;long delay;
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{
            calls.incrementAndGet();request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));auth.set(exchange.getRequestHeaders().getFirst("Authorization"));path.set(exchange.getRequestURI().getPath());
            try{if(delay>0)Thread.sleep(delay);String body=status==200?mapper.writeValueAsString(Map.of("id","qa","object","chat.completion","created",1,"model","qa-model","choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",content),"finish_reason","stop")))):"secret-provider-error-body";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){}finally{exchange.close();}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    ModelSettings.Profile profile(){return new ModelSettings.Profile(UUID.randomUUID().toString(),"测试服务","compatible","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","qa-model",null);}
    @Test void requestContainsOnlySelectedTextAndDoesNotInvokeToolsOrFallback()throws Exception{
        String source="参与事务处理与版本恢复。";var gateway=new ModelGateway(mapper);assertThat(gateway.rewrite(profile(),"secret-test-key",source)).isEqualTo("实现清晰的事务处理与版本恢复。");
        var body=mapper.readTree(request.get());assertThat(body.path("messages")).hasSize(2);assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo(source);
        assertThat(request.get()).doesNotContain("secret-test-key","resumeId","photo","email","tools");assertThat(auth.get()).isEqualTo("Bearer secret-test-key");assertThat(path.get()).isEqualTo("/v1/chat/completions");assertThat(calls.get()).isEqualTo(1);
    }
    @Test void rejectedCredentialsAreSanitizedAndNeverRetried(){status=401;assertThatThrownBy(()->new ModelGateway(mapper).rewrite(profile(),"secret-test-key","原文")).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code).isEqualTo("MODEL_AUTH_FAILED");assertThat(e.getMessage()).doesNotContain("secret-provider-error-body","secret-test-key");});assertThat(calls.get()).isEqualTo(1);}
    @Test void invalidAndOversizedResponsesPreserveTheCallingContract(){
        content="plain response";assertThatThrownBy(()->new ModelGateway(mapper).rewrite(profile(),"secret-test-key","原文")).isInstanceOf(ApiException.class);
        content="{\"text\":\""+"文".repeat(801)+"\"}";assertThatThrownBy(()->new ModelGateway(mapper).rewrite(profile(),"secret-test-key","原文")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("MODEL_RESPONSE_INVALID"));
        content="x".repeat(70000);assertThatThrownBy(()->new ModelGateway(mapper).rewrite(profile(),"secret-test-key","原文")).isInstanceOf(ApiException.class);
    }
    @Test void excessiveExpansionFromARealisticResumePromptIsRejectedWithoutRetry(){
        String source="整理接口文档，与同组成员核对字段，记录并修复联调问题。";
        content="{\"text\":\""+"主导前后端联调，确保系统稳定运行。".repeat(4)+"\"}";
        assertThatThrownBy(()->new ModelGateway(mapper).rewrite(profile(),"secret-test-key",source))
            .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code).isEqualTo("MODEL_EXPANSION_REJECTED");assertThat(e.getMessage()).contains("原文保留");});
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void overCompressedLongParagraphIsRejectedWhileConciseEditingStillWorks(){
        String concise="我在课程练习里把接口文档整理了一下，后来和同组成员一块儿对字段；有联调问题就记下来、修好。";
        content="{\"text\":\"在课程练习中整理接口文档，与同组成员核对字段；记录并修复联调问题。\"}";
        assertThat(new ModelGateway(mapper).rewrite(profile(),"secret-test-key",concise)).contains("整理接口文档");
        String longSource=concise.repeat(12);
        assertThat(longSource.length()).isLessThan(800);
        assertThatThrownBy(()->new ModelGateway(mapper).rewrite(profile(),"secret-test-key",longSource))
            .isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code).isEqualTo("MODEL_OMISSION_RISK");assertThat(e.getMessage()).contains("原文保留");});
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void timeoutsDoNotRetryOrReturnInventedText(){delay=300;assertThatThrownBy(()->new ModelGateway(mapper,Duration.ofMillis(60)).rewrite(profile(),"secret-test-key","原文")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("MODEL_CONNECTION_FAILED"));assertThat(calls.get()).isEqualTo(1);}
}
