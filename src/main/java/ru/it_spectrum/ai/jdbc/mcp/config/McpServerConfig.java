package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stdio MCP uses a single stdout stream. Keeping synchronous tool execution immediate prevents
 * concurrent boundedElastic tool completions from racing while enqueueing responses.
 */
@Configuration
public class McpServerConfig {

    @Bean
    McpSyncServerCustomizer stdioSyncServerCustomizer() {
        return serverBuilder -> serverBuilder.immediateExecution(true);
    }
}
