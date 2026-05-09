package ru.it_spectrum.ai.jdbc.mcp.model.plan;

public record PlanNodeSummary(
        String node_type,
        String relation,
        Double total_cost,
        Long estimated_rows,
        Long actual_rows,
        Double actual_total_time_ms,
        String ranked_by,
        String reason,
        Double ratio,
        String outer_node_type,
        Long outer_rows,
        String sort_method,
        Object sort_space_kb,
        String sort_space_type
) {
}
