package ru.it_spectrum.ai.jdbc.mcp.plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fully parsed execution plan plus top-level metadata (planning / execution time on PG,
 * cost totals on Oracle / SQL Server). {@code engine} is the logical database-kind tag so
 * downstream tooling can branch on "postgresql" / "oracle" / "mssql" without importing the
 * dialect layer.
 */
public record ParsedPlan(String engine, PlanNode root, boolean analyzed, Double planningTimeMs, Double executionTimeMs,
                         Map<String, Object> meta) {

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

    /**
     * {@code true} iff the plan has measured (ANALYZE) rows/times attached.
     */
    @Override
    public boolean analyzed() {
        return analyzed;
    }
}
