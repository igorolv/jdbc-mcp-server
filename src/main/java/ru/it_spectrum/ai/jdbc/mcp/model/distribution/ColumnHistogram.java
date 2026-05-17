package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Percentile and null summary for an orderable column.")
public record ColumnHistogram(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "Column whose percentile distribution was measured.", nullable = true)
        String column,
        @Schema(description = "Database type of the column used to choose percentile behavior.", nullable = true)
        String columnType,
        @Schema(description = "Database percentile function used, such as continuous or discrete percentile.", nullable = true)
        String percentileFunction,
        @Schema(description = "Total number of rows considered for this statistic.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long totalRows,
        @Schema(description = "Number of rows where the column value is not NULL.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long nonNullRows,
        @Schema(description = "Number of rows where the column value is NULL.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        long nullRows,
        @Schema(description = "Share of rows where the column value is NULL, from 0.0 to 1.0.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        double nullRatio,
        @Schema(description = "Minimum observed non-null column value.", nullable = true)
        Object min,
        @Schema(description = "Maximum observed non-null column value.", nullable = true)
        Object max,
        @Schema(description = "25th percentile value for the column.", nullable = true)
        Object p25,
        @Schema(description = "Median, or 50th percentile, value for the column.", nullable = true)
        Object p50,
        @Schema(description = "75th percentile value for the column.", nullable = true)
        Object p75,
        @Schema(description = "90th percentile value for the column.", nullable = true)
        Object p90,
        @Schema(description = "95th percentile value for the column.", nullable = true)
        Object p95,
        @Schema(description = "99th percentile value for the column.", nullable = true)
        Object p99
) {}
