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
 * <p>Fields that do not apply to the engine — for instance, Oracle plans have no
 * {@code actualRows}/{@code actualTotalTime} because Oracle's EXPLAIN is static — are left
 * as {@code null}.
 */
public final class PlanNode {

    private final String nodeType;
    private final String relation;
    private final Double totalCost;
    private final Double startupCost;
    private final Long estimatedRows;
    private final Long actualRows;
    private final Long actualLoops;
    private final Double actualTotalTimeMs;
    private final Map<String, Object> raw;
    private final List<PlanNode> children;

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

    public String nodeType()           { return nodeType; }
    public String relation()           { return relation; }
    public Double totalCost()          { return totalCost; }
    public Double startupCost()        { return startupCost; }
    public Long   estimatedRows()      { return estimatedRows; }
    public Long   actualRows()         { return actualRows; }
    public Long   actualLoops()        { return actualLoops; }
    public Double actualTotalTimeMs()  { return actualTotalTimeMs; }
    public Map<String, Object> raw()   { return raw; }
    public List<PlanNode> children()   { return children; }

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
