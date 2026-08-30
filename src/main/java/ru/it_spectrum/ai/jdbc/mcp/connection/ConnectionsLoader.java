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
 * Turns {@code connections.json} into the list of {@link ConnectionDefinition}s the server serves.
 *
 * <p>The file is the only place a connection is defined. An entry needs a {@code url}; every other
 * field falls back to the {@code DEFAULTS} of the properties record it belongs to. String values may
 * reference environment variables as {@code ${VAR}} — see {@link EnvironmentPlaceholders}.
 *
 * <p>Nothing here opens a database connection or a catalog file.
 */
public final class ConnectionsLoader {

    private static final Logger log = LoggerFactory.getLogger(ConnectionsLoader.class);

    private ConnectionsLoader() {
    }

    /** @return configured connections, in file order */
    public static List<ConnectionDefinition> load(JdbcMcpProperties server, ObjectMapper mapper,
                                                  UnaryOperator<String> env) {
        Path connectionsFile = server.resolvedConnectionsFile();
        if (!Files.exists(connectionsFile)) {
            throw new IllegalStateException("No database connections configured: there is no file at "
                    + connectionsFile + ". Create it (or point JDBC_MCP_CONNECTIONS_FILE elsewhere) "
                    + "with at least one entry under \"connections\".");
        }

        ConnectionsFile file = read(connectionsFile, mapper);
        Map<String, ConnectionsFile.Entry> entries =
                file.connections() == null ? Map.of() : file.connections();
        Map<String, ConnectionDefinition> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, ConnectionsFile.Entry> entry : entries.entrySet()) {
            String name = ConnectionDefinition.requireValidName(
                    entry.getKey(), "in " + connectionsFile);
            if (entry.getValue() == null) {
                throw new IllegalStateException("Connection '" + name + "' in " + connectionsFile
                        + " has no settings");
            }
            definitions.put(name, fromFile(name, entry.getValue(), server, env));
        }
        if (definitions.isEmpty()) {
            throw new IllegalStateException("No database connections configured: " + connectionsFile
                    + " defines none under \"connections\".");
        }

        logConfiguration(definitions.values());
        return List.copyOf(definitions.values());
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
                                                 JdbcMcpProperties server,
                                                 UnaryOperator<String> env) {
        JdbcProperties jdbcDefaults = JdbcProperties.DEFAULTS;
        UsageProperties usageDefaults = UsageProperties.DEFAULTS;
        StructureSnapshotProperties snapshotDefaults = StructureSnapshotProperties.DEFAULTS;

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
                        jdbcDefaults.defaultSchema()),
                or(entry.queryTimeoutSeconds(), jdbcDefaults.queryTimeoutSeconds()),
                or(entry.maxRows(), jdbcDefaults.maxRows()),
                or(entry.fetchSize(), jdbcDefaults.fetchSize()),
                or(blankToNull(EnvironmentPlaceholders.resolve(
                                entry.readonlyGuard(), field + ".readonlyGuard", env)),
                        jdbcDefaults.readonlyGuard()),
                or(entry.poolMaximumSize(), jdbcDefaults.poolMaximumSize()),
                or(entry.poolMinimumIdle(), jdbcDefaults.poolMinimumIdle()),
                or(entry.poolConnectionTimeoutMs(), jdbcDefaults.connectionTimeoutMs()),
                or(entry.poolValidationTimeoutMs(), jdbcDefaults.validationTimeoutMs()),
                or(entry.poolIdleTimeoutMs(), jdbcDefaults.idleTimeoutMs()));
        UsageProperties usage = new UsageProperties(
                or(entry.usageCatalogEnabled(), usageDefaults.catalogEnabled()),
                or(EnvironmentPlaceholders.resolveAll(entry.usageCatalogPaths(),
                        field + ".usageCatalogPaths", env), usageDefaults.catalogPaths()),
                or(EnvironmentPlaceholders.resolveAll(entry.usageNativeSchemas(),
                        field + ".usageNativeSchemas", env), usageDefaults.nativeSchemas()),
                or(entry.usageNativeIncludeViews(), usageDefaults.nativeIncludeViews()),
                or(entry.usageNativeIncludeRoutines(), usageDefaults.nativeIncludeRoutines()),
                or(entry.usageNativeIncludeTriggers(), usageDefaults.nativeIncludeTriggers()),
                or(entry.usageNativeMaxObjects(), usageDefaults.nativeMaxObjects()));
        StructureSnapshotProperties snapshot = new StructureSnapshotProperties(
                or(EnvironmentPlaceholders.resolveAll(entry.structureSnapshotSchemas(),
                        field + ".structureSnapshotSchemas", env), snapshotDefaults.schemas()),
                or(entry.structureSnapshotOracleColumnQueryTimeoutSeconds(),
                        snapshotDefaults.oracleColumnQueryTimeoutSeconds()));
        String description = blankToNull(EnvironmentPlaceholders.resolve(
                entry.description(), field + ".description", env));
        return define(name, description, jdbc, server, usage, snapshot);
    }

    private static ConnectionDefinition define(String name, String description, JdbcProperties jdbc,
                                               JdbcMcpProperties server, UsageProperties usage,
                                               StructureSnapshotProperties snapshot) {
        DatabaseKind kind = null;
        String configError = null;
        try {
            kind = DatabaseKind.fromUrl(jdbc.url());
        } catch (IllegalArgumentException e) {
            configError = e.getMessage();
        }
        JdbcMcpProperties catalog = new JdbcMcpProperties(
                server.dataDir(), name, server.connectionsFile());
        return new ConnectionDefinition(name, description, jdbc, catalog, usage, snapshot, kind,
                configError);
    }

    private static void logConfiguration(Iterable<ConnectionDefinition> definitions) {
        List<String> described = new ArrayList<>();
        for (ConnectionDefinition definition : definitions) {
            described.add(definition.name() + " -> " + definition.maskedUrl()
                    + (definition.usable() ? "" : " (unusable: " + definition.configError() + ")"));
        }
        log.info("Configured connections: {}", String.join(", ", described));
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
