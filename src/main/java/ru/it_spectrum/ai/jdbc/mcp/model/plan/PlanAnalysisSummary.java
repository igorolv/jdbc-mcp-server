package ru.it_spectrum.ai.jdbc.mcp.model.plan;

import java.util.List;

public record PlanAnalysisSummary(
        String engine,
        boolean analyzed,
        Double planning_time_ms,
        Double execution_time_ms,
        int node_count,
        PlanNodeSummary root,
        List<PlanNodeSummary> top_expensive_nodes,
        List<PlanNodeSummary> full_scans,
        List<PlanNodeSummary> estimation_errors,
        List<PlanNodeSummary> risky_nested_loops,
        List<PlanNodeSummary> disk_spills
) {
}
