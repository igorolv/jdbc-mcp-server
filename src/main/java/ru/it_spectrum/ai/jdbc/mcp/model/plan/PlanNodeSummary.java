package ru.it_spectrum.ai.jdbc.mcp.model.plan;

public record PlanNodeSummary(
        String nodeType,
        String relation,
        Double totalCost,
        Long estimatedRows,
        Long actualRows,
        Double actualTotalTimeMs,
        String rankedBy,
        String reason,
        Double ratio,
        String outerNodeType,
        Long outerRows,
        String sortMethod,
        Object sortSpaceKb,
        String sortSpaceType
) {
}