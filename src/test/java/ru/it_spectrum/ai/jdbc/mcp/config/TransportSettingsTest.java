package ru.it_spectrum.ai.jdbc.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code JDBC_MCP_TRANSPORT} sets what the Spring AI MCP server needs for its transport. */
class TransportSettingsTest {

    private static StandardEnvironment environment(Map<String, Object> settings) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("test", settings));
        new TransportSettings().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    void stdioIsTheDefault() {
        StandardEnvironment environment = environment(Map.of());

        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(environment.getProperty("spring.ai.mcp.server.stdio")).isEqualTo("true");
    }

    @Test
    void httpStartsAWebServerAndNoStdio() {
        StandardEnvironment environment = environment(Map.of(TransportSettings.PROPERTY, " HTTP ",
                "spring.main.web-application-type", "none", "spring.ai.mcp.server.stdio", "true"));

        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("servlet");
        assertThat(environment.getProperty("spring.ai.mcp.server.stdio")).isEqualTo("false");
        assertThat(environment.getProperty("spring.ai.mcp.server.protocol")).isEqualTo("streamable");
    }

    @Test
    void aDisabledMcpServerIsLeftAlone() {
        StandardEnvironment environment = environment(Map.of(TransportSettings.PROPERTY, "http",
                "spring.ai.mcp.server.enabled", "false", "spring.main.web-application-type", "none"));

        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
    }

    @Test
    void anUnknownTransportIsRejected() {
        assertThatThrownBy(() -> environment(Map.of(TransportSettings.PROPERTY, "sse")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stdio or http");
    }
}
