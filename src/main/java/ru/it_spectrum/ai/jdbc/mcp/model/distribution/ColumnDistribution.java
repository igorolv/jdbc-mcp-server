package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

import java.util.List;

public record ColumnDistribution(
        String schema,
        String table,
        String column,
        @JsonKey("top_n") int topN,
        @JsonKey("total_rows") long totalRows,
        @JsonKey("top_rows") long topRows,
        @JsonKey("top_ratio") double topRatio,
        @JsonKey("other_rows") long otherRows,
        @JsonKey("other_ratio") double otherRatio,
        List<ValueEntry> values
) {
    public record ValueEntry(
            Object value,
            long frequency,
            double ratio
    ) {}
}
