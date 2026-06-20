package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Planner estimate for joining two tables on a pair of columns without executing the join.")
public record JoinCardinality(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromSchema,
        @Schema(description = "Left or preserved source table for the join estimate.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromTable,
        @Schema(description = "Column from the source table used on the left side of the join estimate.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String leftColumn,
        @Schema(description = "Planner row estimate for the source table before joining.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long fromRowEstimate,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String toSchema,
        @Schema(description = "Right or joined target table for the join estimate.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String toTable,
        @Schema(description = "Column from the target table used on the right side of the join estimate.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String rightColumn,
        @Schema(description = "Planner row estimate for the target table before joining.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long toRowEstimate,
        @Schema(description = "Join type used for the estimate or parsed join, such as INNER, LEFT, RIGHT, or FULL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String joinType,
        @Schema(description = "Planner or catalog estimate of rows for this object or operation.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long estimatedRows,
        @Schema(description = "Product of the two side row estimates before applying join selectivity.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long cartesianRows,
        @Schema(description = "Estimated join output divided by cartesian row count, from 0.0 to 1.0 when known.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Double selectivityVsCartesian,
        @Schema(description = "Additional context about support, limits, interpretation, or engine-specific behavior.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String note
) {}
