package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

public record ColumnHistogram(
        String schema,
        String table,
        String column,
        String columnType,
        String percentileFunction,
        long totalRows,
        long nonNullRows,
        long nullRows,
        double nullRatio,
        Object min,
        Object max,
        Object p25,
        Object p50,
        Object p75,
        Object p90,
        Object p95,
        Object p99
) {}
