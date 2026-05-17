package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "SelectivityEstimate response payload.")
public record SelectivityEstimate(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Predicate.", nullable = true)
        String predicate,
        @Schema(description = "Estimated Rows.", nullable = true)
        Long estimatedRows,
        @Schema(description = "Baseline Rows.", nullable = true)
        Long baselineRows,
        @Schema(description = "Selectivity.", nullable = true)
        Double selectivity,
        @Schema(description = "Note.", nullable = true)
        String note
) {}
