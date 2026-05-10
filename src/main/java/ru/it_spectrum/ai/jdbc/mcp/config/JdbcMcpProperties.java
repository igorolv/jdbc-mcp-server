package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Top-level server configuration for the JDBC MCP server.
 *
 * @param dataDir root directory for server-local data (usage catalog, logs).
 *                Defaults to {@code ~/.jdbc-mcp-server} when blank.
 */
@ConfigurationProperties(prefix = "jdbc-mcp")
public record JdbcMcpProperties(String dataDir) {

    public Path resolvedDataDir() {
        String dir = dataDir;
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home", ".") + "/.jdbc-mcp-server";
        }
        if (dir.startsWith("~")) {
            String home = System.getProperty("user.home", ".");
            dir = home + dir.substring(1);
        }
        return Paths.get(dir).normalize();
    }

    public Path usageCatalogDir() {
        return resolvedDataDir().resolve("usage-catalog");
    }

    public Path logsDir() {
        return resolvedDataDir().resolve("logs");
    }
}
