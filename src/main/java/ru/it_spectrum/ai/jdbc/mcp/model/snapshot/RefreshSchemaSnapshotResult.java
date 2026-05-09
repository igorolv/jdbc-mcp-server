package ru.it_spectrum.ai.jdbc.mcp.model.snapshot;

public record RefreshSchemaSnapshotResult(
        String schema,
        String table,
        Integer refreshedTables,
        String tableSchema,
        String tableName,
        Integer listedTables,
        Integer errors,
        Integer limit,
        Boolean truncated,
        Boolean clearedAll,
        long durationMs,
        long ttlSeconds,
        boolean enabled
) {
}
