package ru.it_spectrum.ai.jdbc.mcp.model.plan;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "LLM-friendly execution plan summary highlighting expensive nodes, full scans, estimation errors, nested-loop risks, and sort spills.")
public record PlanAnalysisSummary(
        @Schema(description = "Database engine that produced the result, such as PostgreSQL, Oracle, or SQL Server.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String engine,
        @Schema(description = "True when the plan includes actual execution metrics, not only estimates.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean analyzed,
        @Schema(description = "Planner time reported by the database, in milliseconds when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Double planningTimeMs,
        @Schema(description = "Execution time reported by the database, in milliseconds when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Double executionTimeMs,
        @Schema(description = "Number of table nodes in the schema graph.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int nodeCount,
        @Schema(description = "Root node of the execution plan.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        PlanNodeSummary root,
        @Schema(description = "Plan nodes ranked as most expensive by cost or actual time.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<PlanNodeSummary> topExpensiveNodes,
        @Schema(description = "Plan nodes that perform full table or index scans and may deserve attention.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<PlanNodeSummary> fullScans,
        @Schema(description = "Plan nodes where actual rows differ materially from estimated rows.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<PlanNodeSummary> estimationErrors,
        @Schema(description = "Nested-loop nodes that may be expensive because the outer side is large.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<PlanNodeSummary> riskyNestedLoops,
        @Schema(description = "Sort or hash nodes that appear to spill to disk.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<PlanNodeSummary> diskSpills
) {
}