package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "ColumnHistogram response payload.")
public record ColumnHistogram(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Column.", nullable = true)
        String column,
        @Schema(description = "Column Type.", nullable = true)
        String columnType,
        @Schema(description = "Percentile Function.", nullable = true)
        String percentileFunction,
        @Schema(description = "Total Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Non Null Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long nonNullRows,
        @Schema(description = "Null Rows.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long nullRows,
        @Schema(description = "Null Ratio.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double nullRatio,
        @Schema(description = "Min.", nullable = true)
        Object min,
        @Schema(description = "Max.", nullable = true)
        Object max,
        @Schema(description = "P25.", nullable = true)
        Object p25,
        @Schema(description = "P50.", nullable = true)
        Object p50,
        @Schema(description = "P75.", nullable = true)
        Object p75,
        @Schema(description = "P90.", nullable = true)
        Object p90,
        @Schema(description = "P95.", nullable = true)
        Object p95,
        @Schema(description = "P99.", nullable = true)
        Object p99
) {}
