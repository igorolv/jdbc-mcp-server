package ru.it_spectrum.ai.jdbc.mcp.model.plan;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "PlanNodeSummary response payload.")
public record PlanNodeSummary(
        @Schema(description = "Node Type.", nullable = true)
        String nodeType,
        @Schema(description = "Relation.", nullable = true)
        String relation,
        @Schema(description = "Total Cost.", nullable = true)
        Double totalCost,
        @Schema(description = "Estimated Rows.", nullable = true)
        Long estimatedRows,
        @Schema(description = "Actual Rows.", nullable = true)
        Long actualRows,
        @Schema(description = "Actual Total Time Ms.", nullable = true)
        Double actualTotalTimeMs,
        @Schema(description = "Ranked By.", nullable = true)
        String rankedBy,
        @Schema(description = "Reason.", nullable = true)
        String reason,
        @Schema(description = "Ratio.", nullable = true)
        Double ratio,
        @Schema(description = "Outer Node Type.", nullable = true)
        String outerNodeType,
        @Schema(description = "Outer Rows.", nullable = true)
        Long outerRows,
        @Schema(description = "Sort Method.", nullable = true)
        String sortMethod,
        @Schema(description = "Sort Space Kb.", nullable = true)
        Object sortSpaceKb,
        @Schema(description = "Sort Space Type.", nullable = true)
        String sortSpaceType
) {
}