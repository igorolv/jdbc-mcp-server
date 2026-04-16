package ru.it_spectrum.ai.jdbc.mcp.plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fully parsed execution plan plus top-level metadata (planning / execution time on PG,
 * cost totals on Oracle). {@code engine} is the logical database-kind tag so downstream
 * tooling can branch on "pg" / "oracle" without importing the dialect layer.
 */
public final class ParsedPlan {

    private final String engine;
    private final PlanNode root;
    private final boolean analyzed;
    private final Double planningTimeMs;
    private final Double executionTimeMs;
    private final Map<String, Object> meta;

    public ParsedPlan(String engine, PlanNode root, boolean analyzed,
                      Double planningTimeMs, Double executionTimeMs,
                      Map<String, Object> meta) {
        this.engine = engine;
        this.root = root;
        this.analyzed = analyzed;
        this.planningTimeMs = planningTimeMs;
        this.executionTimeMs = executionTimeMs;
        this.meta = meta == null ? new LinkedHashMap<>() : meta;
    }

    public String engine()              { return engine; }
    public PlanNode root()              { return root; }
    /** {@code true} iff the plan has measured (ANALYZE) rows/times attached. Always false on Oracle. */
    public boolean analyzed()           { return analyzed; }
    public Double planningTimeMs()      { return planningTimeMs; }
    public Double executionTimeMs()     { return executionTimeMs; }
    public Map<String, Object> meta()   { return meta; }
}
