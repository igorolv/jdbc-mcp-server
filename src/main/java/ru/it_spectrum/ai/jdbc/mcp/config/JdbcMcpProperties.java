package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Top-level server configuration for the JDBC MCP server.
 *
 * @param dataDir     root directory for server-local data (usage catalog, logs).
 *                    Defaults to {@code ~/.jdbc-mcp-server} when blank.
 * @param catalogName name of the local catalog (knowledge store) for the inspected database.
 *                    It is the slot key under which everything we compute about a DB is kept
 *                    (usage index now, persistent structure/stats snapshot later) — NOT the JDBC
 *                    connection. When blank, the server runs in <em>legacy</em> mode: the logical
 *                    name is {@code default} and paths stay byte-identical to the pre-catalog layout.
 */
@ConfigurationProperties(prefix = "jdbc-mcp")
public record JdbcMcpProperties(String dataDir, String catalogName) {

    /** Logical catalog name used when {@code catalogName} is blank (legacy mode). */
    public static final String DEFAULT_CATALOG_NAME = "default";

    /**
     * Backwards-compatible constructor that leaves the catalog unnamed (legacy mode).
     * Used by tests and any caller that predates the catalog-name field.
     */
    public JdbcMcpProperties(String dataDir) {
        this(dataDir, null);
    }

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

    /**
     * {@code true} when no catalog name was configured. In this mode the logical name is
     * {@code default} and the data-dir layout matches the pre-catalog server byte-for-byte.
     */
    public boolean isLegacyCatalog() {
        return catalogName == null || catalogName.isBlank();
    }

    /** Configured catalog name, or {@code default} in legacy mode. */
    public String resolvedCatalogName() {
        return isLegacyCatalog() ? DEFAULT_CATALOG_NAME : catalogName.trim();
    }

    /**
     * Root directory for this catalog's local storage.
     *
     * <p>Legacy mode: the shared {@link #resolvedDataDir()} itself (so {@link #usageCatalogDir()}
     * keeps resolving to {@code <data-dir>/usage-catalog} exactly as before). Named catalogs live
     * under {@code <data-dir>/catalogs/<name>}.
     */
    public Path catalogDir() {
        if (isLegacyCatalog()) {
            return resolvedDataDir();
        }
        return resolvedDataDir().resolve("catalogs").resolve(resolvedCatalogName());
    }

    public Path usageCatalogDir() {
        return catalogDir().resolve("usage-catalog");
    }
}
