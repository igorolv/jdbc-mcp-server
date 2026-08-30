package ru.it_spectrum.ai.jdbc.mcp.connection;

import ru.it_spectrum.ai.jdbc.mcp.config.DataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;

import java.nio.file.Files;
import java.util.regex.Pattern;

/**
 * One named database this server can talk to: the effective settings for its JDBC pool and for its
 * local catalog under {@code <data-dir>/<name>/}.
 *
 * <p>A definition is inert — building it neither contacts the database nor touches the local
 * catalog. {@link #kind()} is derived from the URL prefix alone, and an unsupported or missing URL
 * is recorded in {@link #configError()} instead of being thrown: one broken entry must not stop the
 * server from serving the other connections.
 *
 * @param name             connection name, also the local catalog directory name
 * @param description      free-form text from {@code connections.json}, surfaced by {@code listConnections}
 * @param jdbc             effective JDBC settings (file values merged over the global env defaults)
 * @param catalog          local-catalog settings; {@code catalogName} equals {@link #name()}
 * @param usage            effective usage-catalog settings
 * @param structureSnapshot effective structure-snapshot settings
 * @param kind             engine detected from the URL, {@code null} when the URL is unusable
 * @param configError      why this connection cannot be used, {@code null} when it is fine
 */
public record ConnectionDefinition(
        String name,
        String description,
        JdbcProperties jdbc,
        JdbcMcpProperties catalog,
        UsageProperties usage,
        StructureSnapshotProperties structureSnapshot,
        DatabaseKind kind,
        String configError
) {

    /** Connection names end up in filesystem paths and in resource URIs, so keep them boring. */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Validates a connection name, returning it unchanged. */
    public static String requireValidName(String name, String origin) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalStateException("Invalid connection name '" + name + "' (" + origin
                    + "). Names must match " + NAME_PATTERN.pattern()
                    + " — they are used as directory names and in MCP resource URIs.");
        }
        return name;
    }

    /** True when this connection can be opened at all. */
    public boolean usable() {
        return configError == null;
    }

    /** JDBC URL with any {@code password=} parameter masked — safe for logs and responses. */
    public String maskedUrl() {
        return DataSourceConfig.maskUrl(jdbc.url());
    }

    /** True when {@code <data-dir>/<name>/<name>.db} already exists on disk. */
    public boolean hasLocalSnapshot() {
        return Files.exists(catalog.catalogDbFile());
    }
}
