package dev.localresume;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuntimeInformationTest {
    @TempDir Path directory;

    @Configuration @EnableWebMvc
    @ComponentScan(basePackages="dev.localresume", useDefaultFilters=false,
        includeFilters=@ComponentScan.Filter(type=FilterType.REGEX, pattern="dev\\.localresume\\.Runtime(Information|Controller)"))
    static class FixtureConfiguration {
        @Bean BuildProperties buildProperties() {
            var properties=new Properties();
            properties.setProperty("version","0.8.0-SNAPSHOT");
            properties.setProperty("time","2026-10-03T00:00:00Z");
            properties.setProperty("artifact","resume-workbench");
            return new BuildProperties(properties);
        }
    }

    private AnnotationConfigWebApplicationContext context() {
        var context=new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("runtime-fixture", Map.of(
            "resume.data-dir",directory.resolve("data").toString(),
            "resume.runtime-log-dir",directory.resolve("logs").toString(),
            "server.address","127.0.0.1", "RESUME_DB_PASSWORD","runtime-secret-canary")));
        context.register(FixtureConfiguration.class);context.refresh();return context;
    }

    @Test void runtimeEndpointIdentifiesTheActualProcessAndPackagedBuildWithoutExposingCredentials() throws Exception {
        try(var context=context()) {
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new LocalRequestFilter()).build();
            var result=mvc.perform(get("/api/runtime").with(request->{request.setServerName("127.0.0.1");request.setServerPort(58765);request.setLocalPort(18765);return request;}))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.version").value("0.8.0-SNAPSHOT"))
                .andExpect(jsonPath("$.buildTime").value("2026-10-03T00:00:00Z"))
                .andExpect(jsonPath("$.pid").value(ProcessHandle.current().pid()))
                .andExpect(jsonPath("$.port").value(18765))
                .andExpect(jsonPath("$.dataDirectory").value(directory.resolve("data").toAbsolutePath().toString()))
                .andExpect(jsonPath("$.logsDirectory").value(directory.resolve("logs").toAbsolutePath().toString()))
                .andReturn();
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
            assertThat(java.util.UUID.fromString(json.path("instanceId").asText())).isNotNull();
            assertThat(java.time.Instant.parse(json.path("startedAt").asText())).isBeforeOrEqualTo(java.time.Instant.now());
            assertThat(result.getResponse().getContentAsString()).doesNotContain("runtime-secret-canary","RESUME_DB_PASSWORD","encryptedKey");
            assertThat(directory.resolve("data")).doesNotExist();
        }
    }

    @Test void runtimeDetailsRemainUnavailableToCrossSiteRequests() throws Exception {
        try(var context=context()) {
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new LocalRequestFilter()).build();
            mvc.perform(get("/api/runtime").header("Origin","https://unrelated.invalid")
                .with(request->{request.setServerName("127.0.0.1");request.setServerPort(18765);return request;}))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_REQUEST_REQUIRED"));
        }
    }
}
