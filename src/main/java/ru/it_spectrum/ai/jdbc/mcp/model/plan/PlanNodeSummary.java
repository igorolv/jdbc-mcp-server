package ru.it_spectrum.ai.jdbc.mcp.model.plan;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Compact execution-plan node or diagnostic entry with costs, row counts, timing, and the reason it was highlighted.")
public record PlanNodeSummary(
        @Schema(description = "Database plan node type, such as Seq Scan, Hash Join, or Sort.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String nodeType,
        @Schema(description = "Relation or index name associated with the plan node, when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String relation,
        @Schema(description = "Planner total cost estimate for the node.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Double totalCost,
        @Schema(description = "Planner or catalog estimate of rows for this object or operation.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long estimatedRows,
        @Schema(description = "Actual number of rows produced by the node when an analyzed plan is available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long actualRows,
        @Schema(description = "Actual total node time in milliseconds when an analyzed plan is available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Double actualTotalTimeMs,
        @Schema(description = "Metric used to rank or select this plan node for the summary.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String rankedBy,
        @Schema(description = "Reason the object was unresolved or the item was highlighted.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String reason,
        @Schema(description = "Diagnostic ratio, commonly actual rows divided by estimated rows.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Double ratio,
        @Schema(description = "Node type on the outer side of a nested loop diagnostic.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String outerNodeType,
        @Schema(description = "Estimated or actual outer-side row count for a nested loop diagnostic.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long outerRows,
        @Schema(description = "Sort method reported by the database for a sort diagnostic.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String sortMethod,
        @Schema(description = "Sort memory or disk space reported by the database, in kilobytes when known.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Object sortSpaceKb,
        @Schema(description = "Whether the reported sort space was memory, disk, or another engine-specific category.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String sortSpaceType
) {
}