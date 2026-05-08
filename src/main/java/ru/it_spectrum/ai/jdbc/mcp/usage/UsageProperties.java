package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for the local usage catalog (SQLite).
 *
 * <p>The usage catalog is a local SQLite database that stores known SQL queries used by
 * applications and reports against the inspected database, together with their business context
 * (parameters, output fields, where in templates/UI they are displayed). It is fed by the
 * {@code ingestQuery} MCP tool and queried by the {@code findQueriesBy*} family.
 *
 * <p>Writes are local-only: only this SQLite file is modified. The inspected JDBC database
 * remains strictly read-only.
 *
 * @param catalogEnabled when {@code false}, ingest tools return a {@code disabled} error and
 *                       lookup tools return empty results with {@code catalog_enabled=false}
 * @param catalogPath    path to the SQLite file. If blank, defaults to
 *                       {@code ${user.home}/.jdbc-mcp/usage.db}; the parent directory is
 *                       created on first use.
 */
@ConfigurationProperties(prefix = "usage")
public record UsageProperties(
        boolean catalogEnabled,
        String catalogPath
) {

    public Path resolvedCatalogPath() {
        if (catalogPath != null && !catalogPath.isBlank()) {
            return Paths.get(catalogPath);
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = ".";
        }
        return Paths.get(home, ".jdbc-mcp", "usage.db");
    }
}
