package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Basic one-column statistics: row counts, distinct count, and min/max values.")
public record ColumnStats(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Column whose basic statistics were measured.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String column,
        @Schema(description = "Total number of rows considered for this statistic.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Number of rows where the column value is not NULL.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long nonNullRows,
        @Schema(description = "Estimated or exact number of distinct non-null values.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long distinctValues,
        @Schema(description = "Minimum observed non-null value for the column.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Object minValue,
        @Schema(description = "Maximum observed non-null value for the column.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Object maxValue
) {
}