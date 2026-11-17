package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Java SDK 2.0.0 (used by Spring AI 2.0.1) can lose stdio responses when concurrent tool
 * completions race while enqueueing messages on the single stdout stream (FAIL_NON_SERIALIZED /
 * "Failed to enqueue message"). Keep stdio execution sequential until the SDK dependency contains
 * the upstream fix. A slow tool call blocks later calls on the same session, including during a
 * catalog rebuild.
 *
 * <p>Stdio only: the streamable HTTP transport ({@code JDBC_MCP_TRANSPORT=http}) answers each request
 * on its own HTTP response, so there is no shared stream to protect and tool calls run in parallel.
 *
 * @see <a href="https://github.com/modelcontextprotocol/java-sdk/issues/686">MCP Java SDK #686</a>
 * @see <a href="https://github.com/modelcontextprotocol/java-sdk/commit/2bb1481ec3431a598e6d2f3450e646bf6cb86cf4">Upstream fix 2bb1481</a>
 */
@Configuration
public class McpServerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "true")
    McpSyncServerCustomizer stdioSyncServerCustomizer() {
        return serverBuilder -> serverBuilder.immediateExecution(true);
    }
}
