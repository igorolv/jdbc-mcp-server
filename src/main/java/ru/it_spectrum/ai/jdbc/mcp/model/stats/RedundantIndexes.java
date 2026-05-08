package ru.it_spectrum.ai.jdbc.mcp.model.stats;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

import java.util.List;

public record RedundantIndexes(
        String schema,
        String table,
        int count,
        List<Finding> findings
) {
    public record Finding(
            String schema,
            @JsonKey("table") String tableName,
            @JsonKey("shadowed_index") String shadowedIndex,
            @JsonKey("shadowed_columns") String shadowedColumns,
            @JsonKey("shadowed_size_bytes") long shadowedSizeBytes,
            @JsonKey("covered_by_index") String coveredByIndex,
            @JsonKey("covered_by_columns") String coveredByColumns,
            @JsonKey("index_type") String indexType
    ) {}
}
