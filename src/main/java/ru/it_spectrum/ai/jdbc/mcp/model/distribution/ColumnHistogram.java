package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

public record ColumnHistogram(
        String schema,
        String table,
        String column,
        @JsonKey("column_type") String columnType,
        @JsonKey("percentile_function") String percentileFunction,
        @JsonKey("total_rows") long totalRows,
        @JsonKey("non_null_rows") long nonNullRows,
        @JsonKey("null_rows") long nullRows,
        @JsonKey("null_ratio") double nullRatio,
        Object min,
        Object max,
        Object p25,
        Object p50,
        Object p75,
        Object p90,
        Object p95,
        Object p99
) {}
