package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Turns {@code connections.json} plus the global environment defaults into the list of
 * {@link ConnectionDefinition}s the server serves.
 *
 * <p>Backwards compatibility is the first requirement: with no connections file the server behaves
 * exactly as before — one connection built from {@code JDBC_URL} / {@code JDBC_USERNAME} /
 * {@code JDBC_PASSWORD}, named after {@code JDBC_MCP_CATALOG}, and used whenever a tool call omits
 * {@code connection}. When both are present the environment connection is added to the registry
 * alongside the file's entries, and a name clash is a startup error rather than a silent override.
 *
 * <p>Nothing here opens a database connection or a catalog file.
 */
public final class ConnectionsLoader {

    private static final Logger log = LoggerFactory.getLogger(ConnectionsLoader.class);

    private ConnectionsLoader() {
    }

    /**
     * @param definitions       configured connections, in file order (environment connection last)
     * @param defaultConnection explicitly configured default, or {@code null}
     */
    public record Loaded(List<ConnectionDefinition> definitions, String defaultConnection) {
    }

    public static Loaded load(Path connectionsFile,
                              JdbcProperties globalJdbc,
                              JdbcMcpProperties globalCatalog,
                              UsageProperties globalUsage,
                              StructureSnapshotProperties globalSnapshot,
                              ObjectMapper mapper,
                              UnaryOperator<String> env) {
        Map<String, ConnectionDefinition> definitions = new LinkedHashMap<>();
        String defaultConnection = null;

        if (connectionsFile != null && Files.exists(connectionsFile)) {
            ConnectionsFile file = read(connectionsFile, mapper);
            Map<String, ConnectionsFile.Entry> entries =
                    file.connections() == null ? Map.of() : file.connections();
            for (Map.Entry<String, ConnectionsFile.Entry> entry : entries.entrySet()) {
                String name = ConnectionDefinition.requireValidName(
                        entry.getKey(), "in " + connectionsFile);
                if (entry.getValue() == null) {
                    throw new IllegalStateException("Connection '" + name + "' in " + connectionsFile
                            + " has no settings");
                }
                definitions.put(name, fromFile(name, entry.getValue(), globalJdbc, globalCatalog,
                        globalUsage, globalSnapshot, env));
            }
            defaultConnection = EnvironmentPlaceholders.resolve(
                    file.defaultConnection(), "defaultConnection", env);
            if (defaultConnection != null && defaultConnection.isBlank()) {
                defaultConnection = null;
            }
        }

        if (hasEnvironmentConnection(globalJdbc)) {
            String name = ConnectionDefinition.requireValidName(
                    globalCatalog.resolvedCatalogName(), "from JDBC_MCP_CATALOG");
            if (definitions.containsKey(name)) {
                throw new IllegalStateException("Connection name '" + name + "' is defined both in "
                        + connectionsFile + " and by the JDBC_URL / JDBC_MCP_CATALOG environment "
                        + "variables. Rename one of them, or drop the environment variables.");
            }
            definitions.put(name, fromEnvironment(name, globalJdbc, globalCatalog, globalUsage,
                    globalSnapshot));
        }

        if (definitions.isEmpty()) {
            throw new IllegalStateException("No database connections configured. Set JDBC_URL / "
                    + "JDBC_USERNAME / JDBC_PASSWORD, or create a connections file at "
                    + connectionsFile + " (override the path with JDBC_MCP_CONNECTIONS_FILE).");
        }
        if (defaultConnection != null && !definitions.containsKey(defaultConnection)) {
            throw new IllegalStateException("defaultConnection '" + defaultConnection + "' in "
                    + connectionsFile + " is not one of the configured connections: "
                    + String.join(", ", definitions.keySet()));
        }

        logConfiguration(definitions.values(), defaultConnection);
        return new Loaded(List.copyOf(definitions.values()), defaultConnection);
    }

    private static boolean hasEnvironmentConnection(JdbcProperties globalJdbc) {
        return globalJdbc != null && globalJdbc.url() != null && !globalJdbc.url().isBlank();
    }

    private static ConnectionsFile read(Path path, ObjectMapper mapper) {
        try {
            ConnectionsFile file = mapper.readValue(Files.readString(path), ConnectionsFile.class);
            if (file == null) {
                throw new IllegalStateException("Connections file " + path + " is empty");
            }
            return file;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read connections file " + path + ": "
                    + e.getMessage(), e);
        }
    }

    private static ConnectionDefinition fromFile(String name, ConnectionsFile.Entry entry,
                                                 JdbcProperties globalJdbc,
                                                 JdbcMcpProperties globalCatalog,
                                                 UsageProperties globalUsage,
                                                 StructureSnapshotProperties globalSnapshot,
                                                 UnaryOperator<String> env) {
        String field = "connections." + name;
        String url = EnvironmentPlaceholders.resolve(entry.url(), field + ".url", env);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Connection '" + name + "' has no 'url'");
        }
        JdbcProperties jdbc = new JdbcProperties(
                url,
                EnvironmentPlaceholders.resolve(entry.username(), field + ".username", env),
                EnvironmentPlaceholders.resolve(entry.password(), field + ".password", env),
                or(blankToNull(EnvironmentPlaceholders.resolve(
                                entry.defaultSchema(), field + ".defaultSchema", env)),
                        globalJdbc.defaultSchema()),
                or(entry.queryTimeoutSeconds(), globalJdbc.queryTimeoutSeconds()),
                or(entry.maxRows(), globalJdbc.maxRows()),
                or(entry.fetchSize(), globalJdbc.fetchSize()),
                or(blankToNull(EnvironmentPlaceholders.resolve(
                                entry.readonlyGuard(), field + ".readonlyGuard", env)),
                        globalJdbc.readonlyGuard()),
                or(entry.poolMaximumSize(), globalJdbc.poolMaximumSize()),
                or(entry.poolMinimumIdle(), globalJdbc.poolMinimumIdle()),
                or(entry.poolConnectionTimeoutMs(), globalJdbc.connectionTimeoutMs()),
                or(entry.poolValidationTimeoutMs(), globalJdbc.validationTimeoutMs()),
                or(entry.poolIdleTimeoutMs(), globalJdbc.idleTimeoutMs()));
        UsageProperties usage = new UsageProperties(
                or(entry.usageCatalogEnabled(), globalUsage.catalogEnabled()),
                or(EnvironmentPlaceholders.resolveAll(entry.usageCatalogPaths(),
                        field + ".usageCatalogPaths", env), globalUsage.catalogPaths()),
                or(EnvironmentPlaceholders.resolveAll(entry.usageNativeSchemas(),
                        field + ".usageNativeSchemas", env), globalUsage.nativeSchemas()),
                or(entry.usageNativeIncludeViews(), globalUsage.nativeIncludeViews()),
                or(entry.usageNativeIncludeRoutines(), globalUsage.nativeIncludeRoutines()),
                or(entry.usageNativeIncludeTriggers(), globalUsage.nativeIncludeTriggers()),
                or(entry.usageNativeMaxObjects(), globalUsage.nativeMaxObjects()));
        StructureSnapshotProperties snapshot = new StructureSnapshotProperties(
                or(EnvironmentPlaceholders.resolveAll(entry.structureSnapshotSchemas(),
                        field + ".structureSnapshotSchemas", env), globalSnapshot.schemas()),
                or(entry.structureSnapshotOracleColumnQueryTimeoutSeconds(),
                        globalSnapshot.oracleColumnQueryTimeoutSeconds()));
        String description = blankToNull(EnvironmentPlaceholders.resolve(
                entry.description(), field + ".description", env));
        return define(name, description, jdbc, globalCatalog, usage, snapshot);
    }

    private static ConnectionDefinition fromEnvironment(String name, JdbcProperties globalJdbc,
                                                        JdbcMcpProperties globalCatalog,
                                                        UsageProperties globalUsage,
                                                        StructureSnapshotProperties globalSnapshot) {
        return define(name, null, globalJdbc, globalCatalog, globalUsage, globalSnapshot);
    }

    private static ConnectionDefinition define(String name, String description, JdbcProperties jdbc,
                                               JdbcMcpProperties globalCatalog, UsageProperties usage,
                                               StructureSnapshotProperties snapshot) {
        DatabaseKind kind = null;
        String configError = null;
        try {
            kind = DatabaseKind.fromUrl(jdbc.url());
        } catch (IllegalArgumentException e) {
            configError = e.getMessage();
        }
        JdbcMcpProperties catalog = new JdbcMcpProperties(
                globalCatalog.dataDir(), name, globalCatalog.connectionsFile());
        return new ConnectionDefinition(name, description, jdbc, catalog, usage, snapshot, kind,
                configError);
    }

    private static void logConfiguration(Iterable<ConnectionDefinition> definitions, String defaultConnection) {
        List<String> described = new ArrayList<>();
        for (ConnectionDefinition definition : definitions) {
            described.add(definition.name() + " -> " + definition.maskedUrl()
                    + (definition.usable() ? "" : " (unusable: " + definition.configError() + ")"));
        }
        log.info("Configured connections: {}", String.join(", ", described));
        if (defaultConnection != null) {
            log.info("Default connection: {}", defaultConnection);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static int or(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static boolean or(Boolean value, boolean fallback) {
        return value != null ? value : fallback;
    }
}
