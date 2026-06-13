package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Top-level server configuration for the JDBC MCP server.
 *
 * @param dataDir     root directory for server-local data (shared across catalogs).
 *                    Defaults to {@code ~/.jdbc-mcp-server} when blank.
 * @param catalogName name of the local catalog (knowledge store) for the inspected database.
 *                    It is the slot key under which everything we keep about a DB lives
 *                    ({@code usage-catalog/}, {@code logs/}, and the future persisted snapshot),
 *                    all rooted at {@code <data-dir>/<name>/}. NOT the JDBC connection. Defaults to
 *                    {@code default} when blank. Run several databases by launching one server
 *                    instance per database, each with its own catalog name.
 */
@ConfigurationProperties(prefix = "jdbc-mcp")
public record JdbcMcpProperties(String dataDir, String catalogName) {

    /** Catalog name used when {@code catalogName} is blank. */
    public static final String DEFAULT_CATALOG_NAME = "default";

    /**
     * Marks the canonical constructor as the one Spring uses for property binding. Required because
     * the extra {@link #JdbcMcpProperties(String)} constructor makes the binding constructor
     * ambiguous; without this Spring falls back to JavaBean binding and fails (records have no
     * no-arg constructor).
     */
    @ConstructorBinding
    public JdbcMcpProperties(String dataDir, String catalogName) {
        this.dataDir = dataDir;
        this.catalogName = catalogName;
    }

    /**
     * Backwards-compatible constructor that leaves the catalog unnamed (resolves to
     * {@link #DEFAULT_CATALOG_NAME}). Used by tests and callers that predate the catalog-name field.
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

    /** Configured catalog name, or {@link #DEFAULT_CATALOG_NAME} when blank. */
    public String resolvedCatalogName() {
        return (catalogName == null || catalogName.isBlank())
                ? DEFAULT_CATALOG_NAME
                : catalogName.trim();
    }

    /** Root directory for this catalog's local storage: {@code <data-dir>/<name>}. */
    public Path catalogDir() {
        return resolvedDataDir().resolve(resolvedCatalogName());
    }

    /**
     * Base path (no extension) of this catalog's H2 database, used to build the JDBC URL:
     * {@code <data-dir>/<name>/<name>}. H2's MVStore engine appends {@code .mv.db}.
     */
    public Path catalogDbBase() {
        return catalogDir().resolve(resolvedCatalogName());
    }

    /** The persistent catalog database file on disk: {@code <data-dir>/<name>/<name>.mv.db}. */
    public Path catalogDbFile() {
        return catalogDir().resolve(resolvedCatalogName() + ".mv.db");
    }

    /** Usage-catalog source/index directory: {@code <data-dir>/<name>/usage-catalog}. */
    public Path usageCatalogDir() {
        return catalogDir().resolve("usage-catalog");
    }

    /** Log directory for this catalog: {@code <data-dir>/<name>/logs}. */
    public Path logsDir() {
        return catalogDir().resolve("logs");
    }
}
