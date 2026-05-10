package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

public record ColumnStats(
        String schema,
        String table,
        String column,
        long totalRows,
        long nonNullRows,
        long distinctValues,
        Object minValue,
        Object maxValue
) {
}