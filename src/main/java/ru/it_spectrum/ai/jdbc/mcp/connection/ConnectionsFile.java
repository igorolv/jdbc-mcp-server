package ru.it_spectrum.ai.jdbc.mcp.connection;

import java.util.List;
import java.util.Map;

/**
 * On-disk shape of {@code connections.json}.
 *
 * <p>Every field is optional except {@code url}: what a connection does not override is inherited
 * from the global environment-based defaults ({@code JdbcProperties}, {@code UsageProperties},
 * {@code StructureSnapshotProperties}). String values may reference environment variables as
 * {@code ${VAR}} — see {@link EnvironmentPlaceholders}.
 *
 * @param connections connection name → definition; the name is also the local catalog directory
 *                    under {@code <data-dir>/}
 */
public record ConnectionsFile(
        Map<String, Entry> connections
) {

    /**
     * One connection entry. Fields mirror the global configuration properties they override.
     *
     * @param description free-form text shown by {@code listConnections} so an agent can pick a
     *                    database by meaning rather than by name
     */
    public record Entry(
            String url,
            String username,
            String password,
            String description,
            String defaultSchema,
            Integer queryTimeoutSeconds,
            Integer maxRows,
            Integer fetchSize,
            String readonlyGuard,
            Integer poolMaximumSize,
            Integer poolMinimumIdle,
            Integer poolConnectionTimeoutMs,
            Integer poolValidationTimeoutMs,
            Integer poolIdleTimeoutMs,
            List<String> structureSnapshotSchemas,
            Integer structureSnapshotOracleColumnQueryTimeoutSeconds,
            Boolean usageCatalogEnabled,
            List<String> usageCatalogPaths,
            List<String> usageNativeSchemas,
            Boolean usageNativeIncludeViews,
            Boolean usageNativeIncludeRoutines,
            Boolean usageNativeIncludeTriggers,
            Integer usageNativeMaxObjects
    ) {
    }
}
