package ru.it_spectrum.ai.jdbc.mcp.model.plan;

import java.util.List;

public record PlanAnalysisSummary(
        String engine,
        boolean analyzed,
        Double planningTimeMs,
        Double executionTimeMs,
        int nodeCount,
        PlanNodeSummary root,
        List<PlanNodeSummary> topExpensiveNodes,
        List<PlanNodeSummary> fullScans,
        List<PlanNodeSummary> estimationErrors,
        List<PlanNodeSummary> riskyNestedLoops,
        List<PlanNodeSummary> diskSpills
) {
}