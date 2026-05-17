package ru.it_spectrum.ai.jdbc.mcp.model.plan;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "PlanAnalysisSummary response payload.")
public record PlanAnalysisSummary(
        @Schema(description = "Engine.", nullable = true)
        String engine,
        @Schema(description = "Analyzed.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean analyzed,
        @Schema(description = "Planning Time Ms.", nullable = true)
        Double planningTimeMs,
        @Schema(description = "Execution Time Ms.", nullable = true)
        Double executionTimeMs,
        @Schema(description = "Node Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int nodeCount,
        @Schema(description = "Root.", nullable = true)
        PlanNodeSummary root,
        @Schema(description = "Top Expensive Nodes.", nullable = true)
        List<PlanNodeSummary> topExpensiveNodes,
        @Schema(description = "Full Scans.", nullable = true)
        List<PlanNodeSummary> fullScans,
        @Schema(description = "Estimation Errors.", nullable = true)
        List<PlanNodeSummary> estimationErrors,
        @Schema(description = "Risky Nested Loops.", nullable = true)
        List<PlanNodeSummary> riskyNestedLoops,
        @Schema(description = "Disk Spills.", nullable = true)
        List<PlanNodeSummary> diskSpills
) {
}