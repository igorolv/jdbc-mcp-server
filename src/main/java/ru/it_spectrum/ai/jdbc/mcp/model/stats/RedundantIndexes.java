package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import java.util.List;

public record RedundantIndexes(
        String schema,
        String table,
        int count,
        List<Finding> findings
) {
    public record Finding(
            String schema,
            String tableName,
            String shadowedIndex,
            String shadowedColumns,
            long shadowedSizeBytes,
            String coveredByIndex,
            String coveredByColumns,
            String indexType
    ) {}
}
