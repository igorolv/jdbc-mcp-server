package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Planner-only estimate of how selective a table predicate is.")
public record SelectivityEstimate(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Table on which the predicate selectivity was estimated.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Raw SQL predicate without the WHERE keyword used for selectivity estimation.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String predicate,
        @Schema(description = "Planner or catalog estimate of rows for this object or operation.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long estimatedRows,
        @Schema(description = "Planner row estimate for the table without the predicate.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long baselineRows,
        @Schema(description = "Estimated predicate selectivity, calculated as estimated rows divided by baseline rows.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Double selectivity,
        @Schema(description = "Additional context about support, limits, interpretation, or engine-specific behavior.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String note
) {}
