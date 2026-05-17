package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ColumnDistribution response payload.")
public record ColumnDistribution(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Column.", nullable = true)
        String column,
        @Schema(description = "Top N.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int topN,
        @Schema(description = "Total Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Top Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long topRows,
        @Schema(description = "Top Ratio.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double topRatio,
        @Schema(description = "Other Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long otherRows,
        @Schema(description = "Other Ratio.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double otherRatio,
        @Schema(description = "Values.", nullable = true)
        List<ValueEntry> values
) {
    @Schema(description = "ValueEntry response payload.")
    public record ValueEntry(
            @Schema(description = "Value.", nullable = true)
            Object value,
            @Schema(description = "Frequency.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            long frequency,
            @Schema(description = "Ratio.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            double ratio
    ) {}
}
