package ru.it_spectrum.ai.jdbc.mcp.model.snapshot;

public record RefreshSchemaSnapshotResult(
        String schema,
        String table,
        String tableSchema,
        String tableName,
        Integer limit,
        Boolean truncated,
        long ttlSeconds,
        boolean enabled
) {
}
