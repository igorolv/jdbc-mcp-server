package ru.it_spectrum.ai.jdbc.mcp.connection;

import java.util.List;
import java.util.Map;

/**
 * On-disk shape of {@code connections.json}.
 *
 * <p>Every field is optional except {@code url}: what a connection leaves out falls back to the
 * built-in {@code DEFAULTS} of {@code JdbcProperties}, {@code UsageProperties} and
 * {@code StructureSnapshotProperties}. String values may reference environment variables as
 * {@code ${VAR}} — see {@link EnvironmentPlaceholders}.
 *
 * @param connections connection name → definition; the name is also the local catalog directory
 *                    under {@code <data-dir>/}
 */
public record ConnectionsFile(
        Map<String, Entry> connections
) {

    /**
     * One connection entry. Fields mirror the properties records they populate.
     *
     * @param description free-form text shown by {@code listConnections} so an agent can pick a
     *                    database by meaning rather than by name
     * @param dialect     explicit engine, overriding detection from the URL — see
     *                    {@link ru.it_spectrum.ai.jdbc.mcp.config.DriverProperties}
     * @param driverPath  driver jar or directory of jars for a database without a bundled driver
     * @param driverClass driver class inside {@code driverPath}; optional
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
            Integer usageNativeMaxObjects,
            String dialect,
            String driverPath,
            String driverClass
    ) {
    }
}
