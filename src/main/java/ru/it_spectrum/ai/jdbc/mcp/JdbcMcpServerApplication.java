package ru.it_spectrum.ai.jdbc.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The root context holds only what is shared across databases: the MCP tool routers, JSON support,
 * the global configuration defaults and the {@code ConnectionRegistry}. Everything bound to a
 * single database — {@code dialect}, {@code metadata}, {@code sql} and {@code usage} — is scanned
 * into a per-connection child context instead, which is why those packages are deliberately absent
 * from {@code scanBasePackages}.
 */
@SpringBootApplication(scanBasePackages = {
        "ru.it_spectrum.ai.jdbc.mcp.config",
        "ru.it_spectrum.ai.jdbc.mcp.resource",
        "ru.it_spectrum.ai.jdbc.mcp.tools",
})
public class JdbcMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JdbcMcpServerApplication.class, args);
    }
}
