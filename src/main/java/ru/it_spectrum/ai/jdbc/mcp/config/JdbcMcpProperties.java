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
 * @param catalogName name of the local catalog (knowledge store) for one connection: the slot key
 *                    under which everything we keep about that database lives
 *                    ({@code usage-catalog/} and the persisted snapshot), all rooted at
 *                    {@code <data-dir>/<name>/}. Always equals the connection name; blank only for
 *                    the root context, which has no single database.
 * @param connectionsFile path of the JSON file describing the named connections this server serves.
 *                    Defaults to {@code <data-dir>/connections.json} when blank.
 */
@ConfigurationProperties(prefix = "jdbc-mcp")
public record JdbcMcpProperties(String dataDir, String catalogName, String connectionsFile) {

    /** Catalog name used when {@code catalogName} is blank. */
    public static final String DEFAULT_CATALOG_NAME = "default";

    /**
     * Marks the canonical constructor as the one Spring uses for property binding. Required because
     * the extra convenience constructors make the binding constructor ambiguous; without this
     * Spring falls back to JavaBean binding and fails (records have no no-arg constructor).
     */
    @ConstructorBinding
    public JdbcMcpProperties(String dataDir, String catalogName, String connectionsFile) {
        this.dataDir = dataDir;
        this.catalogName = catalogName;
        this.connectionsFile = connectionsFile;
    }

    /** Catalog-scoped settings without an explicit connections file. */
    public JdbcMcpProperties(String dataDir, String catalogName) {
        this(dataDir, catalogName, null);
    }

    /**
     * Backwards-compatible constructor that leaves the catalog unnamed (resolves to
     * {@link #DEFAULT_CATALOG_NAME}). Used by tests and callers that predate the catalog-name field.
     */
    public JdbcMcpProperties(String dataDir) {
        this(dataDir, null, null);
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

    /** The persistent SQLite catalog file: {@code <data-dir>/<name>/<name>.db}. */
    public Path catalogDbFile() {
        return catalogDir().resolve(resolvedCatalogName() + ".db");
    }

    /** Legacy H2 catalog left in place when upgrading to SQLite. */
    public Path legacyH2CatalogDbFile() {
        return catalogDir().resolve(resolvedCatalogName() + ".mv.db");
    }

    /** Usage-catalog source/index directory: {@code <data-dir>/<name>/usage-catalog}. */
    public Path usageCatalogDir() {
        return catalogDir().resolve("usage-catalog");
    }

    /** Path of the connections file: the configured one, or {@code <data-dir>/connections.json}. */
    public Path resolvedConnectionsFile() {
        if (connectionsFile == null || connectionsFile.isBlank()) {
            return resolvedDataDir().resolve("connections.json");
        }
        String path = connectionsFile.trim();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home", ".") + path.substring(1);
        }
        return Paths.get(path).normalize();
    }

    /** Shared process log directory: {@code <data-dir>/logs}. */
    public Path logsDir() {
        return resolvedDataDir().resolve("logs");
    }
}
