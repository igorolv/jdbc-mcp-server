package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "ColumnStats response payload.")
public record ColumnStats(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Column.", nullable = true)
        String column,
        @Schema(description = "Total Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Non Null Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long nonNullRows,
        @Schema(description = "Distinct Values.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long distinctValues,
        @Schema(description = "Min Value.", nullable = true)
        Object minValue,
        @Schema(description = "Max Value.", nullable = true)
        Object maxValue
) {
}