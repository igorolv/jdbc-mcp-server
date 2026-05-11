package ru.it_spectrum.ai.jdbc.mcp.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unified, engine-agnostic representation of a single node in an execution plan.
 *
 * <p>Only the fields that the analyser actually reasons over are extracted into typed
 * accessors; everything else (buffer counters, predicate strings, partition info, …) lives
 * in {@link #raw()} for faithful pass-through to the caller.
 *
 * <p>Fields that do not apply to the engine — for instance, Oracle and SQL Server estimated
 * plans have no {@code actualRows}/{@code actualTotalTime} — are left as {@code null}.
 */
public record PlanNode(String nodeType, String relation, Double totalCost, Double startupCost, Long estimatedRows,
                       Long actualRows, Long actualLoops, Double actualTotalTimeMs, Map<String, Object> raw,
                       List<PlanNode> children) {

    public PlanNode(String nodeType, String relation,
                    Double totalCost, Double startupCost,
                    Long estimatedRows, Long actualRows, Long actualLoops,
                    Double actualTotalTimeMs,
                    Map<String, Object> raw, List<PlanNode> children) {
        this.nodeType = nodeType;
        this.relation = relation;
        this.totalCost = totalCost;
        this.startupCost = startupCost;
        this.estimatedRows = estimatedRows;
        this.actualRows = actualRows;
        this.actualLoops = actualLoops;
        this.actualTotalTimeMs = actualTotalTimeMs;
        this.raw = raw;
        this.children = children == null ? new ArrayList<>() : children;
    }

    /**
     * Effective actual rows per call across all loops. PostgreSQL reports per-loop actual rows
     * in some node types — callers often want the total, which is {@code actualRows * actualLoops}.
     */
    public Long actualRowsTotal() {
        if (actualRows == null) return null;
        long loops = actualLoops == null ? 1 : Math.max(1, actualLoops);
        return actualRows * loops;
    }
}
