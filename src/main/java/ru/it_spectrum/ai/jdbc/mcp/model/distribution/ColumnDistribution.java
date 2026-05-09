package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import java.util.List;

public record ColumnDistribution(
        String schema,
        String table,
        String column,
        int topN,
        long totalRows,
        long topRows,
        double topRatio,
        long otherRows,
        double otherRatio,
        List<ValueEntry> values
) {
    public record ValueEntry(
            Object value,
            long frequency,
            double ratio
    ) {}
}
