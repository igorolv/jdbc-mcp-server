package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Top-N frequency distribution for a column, useful for detecting skew and low-selectivity predicates.")
public record ColumnDistribution(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Column whose value frequencies were measured.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String column,
        @Schema(description = "Maximum number of most-frequent values requested for the distribution.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int topN,
        @Schema(description = "Total number of rows considered for this statistic.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Rows covered by the returned top-N value buckets.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long topRows,
        @Schema(description = "Share of all rows covered by the returned top-N buckets, from 0.0 to 1.0.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double topRatio,
        @Schema(description = "Rows not represented by the returned top-N value buckets.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long otherRows,
        @Schema(description = "Share of all rows outside the returned top-N buckets, from 0.0 to 1.0.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double otherRatio,
        @Schema(description = "Most frequent values and their frequencies for the column.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<ValueEntry> values
) {
    @Schema(description = "One value bucket in a column frequency distribution.")
    public record ValueEntry(
            @Schema(description = "Column value for this distribution bucket; may be null.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Object value,
            @Schema(description = "Number of rows containing this value.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long frequency,
            @Schema(description = "Share of total rows represented by this value, from 0.0 to 1.0.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            double ratio
    ) {}
}
