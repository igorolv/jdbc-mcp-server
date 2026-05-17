package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JoinCardinality response payload.")
public record JoinCardinality(
        @Schema(description = "From Schema.", nullable = true)
        String fromSchema,
        @Schema(description = "From Table.", nullable = true)
        String fromTable,
        @Schema(description = "Left Column.", nullable = true)
        String leftColumn,
        @Schema(description = "From Row Estimate.", nullable = true)
        Long fromRowEstimate,
        @Schema(description = "To Schema.", nullable = true)
        String toSchema,
        @Schema(description = "To Table.", nullable = true)
        String toTable,
        @Schema(description = "Right Column.", nullable = true)
        String rightColumn,
        @Schema(description = "To Row Estimate.", nullable = true)
        Long toRowEstimate,
        @Schema(description = "Join Type.", nullable = true)
        String joinType,
        @Schema(description = "Estimated Rows.", nullable = true)
        Long estimatedRows,
        @Schema(description = "Cartesian Rows.", nullable = true)
        Long cartesianRows,
        @Schema(description = "Selectivity Vs Cartesian.", nullable = true)
        Double selectivityVsCartesian,
        @Schema(description = "Note.", nullable = true)
        String note
) {}
