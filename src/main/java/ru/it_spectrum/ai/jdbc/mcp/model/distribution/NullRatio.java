package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

import java.util.List;

public record NullRatio(
        String schema,
        String table,
        @JsonKey("total_rows") long totalRows,
        List<ColumnEntry> columns
) {
    public record ColumnEntry(
            String column,
            @JsonKey("non_null_rows") long nonNullRows,
            @JsonKey("null_rows") long nullRows,
            @JsonKey("null_ratio") double nullRatio,
            boolean sparse
    ) {}
}
