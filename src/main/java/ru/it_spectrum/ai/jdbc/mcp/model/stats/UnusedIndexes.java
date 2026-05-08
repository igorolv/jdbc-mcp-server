package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

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
            @JsonKey("table") String tableName,
            @JsonKey("index") String indexName,
            String columns,
            @JsonKey("size_bytes") long sizeBytes,
            @JsonKey("index_type") String indexType
    ) {}
}
