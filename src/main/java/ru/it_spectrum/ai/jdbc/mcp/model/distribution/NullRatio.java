package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import java.util.List;

public record NullRatio(
        String schema,
        String table,
        long totalRows,
        List<ColumnEntry> columns
) {
    public record ColumnEntry(
            String column,
            long nonNullRows,
            long nullRows,
            double nullRatio,
            boolean sparse
    ) {}
}
