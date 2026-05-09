package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import java.util.List;

public record UnusedIndexes(
        boolean supported,
        String note,
        String schema,
        int count,
        List<UnusedIndexEntry> indexes
) {
    public record UnusedIndexEntry(
            String schema,
            String tableName,
            String indexName,
            String columns,
            long sizeBytes,
            String indexType
    ) {}
}
