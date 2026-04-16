package ru.it_spectrum.ai.jdbc.mcp.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Walks a {@link ParsedPlan} and produces a compact summary aimed at LLMs: instead of a
 * multi-page EXPLAIN dump, the caller gets a handful of findings — the expensive nodes,
 * full scans, estimation errors, risky nested loops, and any disk spills.
 *
 * <p>Rules are deliberately conservative (no false certainty about what is "bad"). Each
 * finding includes the node type, the affected relation, and the specific numbers that
 * triggered the rule — enough for a follow-up action (add an index, refresh stats, rewrite)
 * without guesswork.
 */
public final class PlanAnalyzer {

    /** Estimation error ratio above which we flag the node. */
    private static final double EST_ERROR_FACTOR = 10.0;

    /** Minimum estimated outer rows for a Nested Loop to be flagged as risky. */
    private static final long NESTED_LOOP_BIG_OUTER_ROWS = 10_000L;

    /** Minimum estimated rows for a full table scan to be called out. */
    private static final long FULL_SCAN_BIG_ROWS = 10_000L;

    private PlanAnalyzer() {}

    public static Map<String, Object> summarize(ParsedPlan plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", plan.engine());
        out.put("analyzed", plan.analyzed());
        if (plan.planningTimeMs() != null)  out.put("planning_time_ms", plan.planningTimeMs());
        if (plan.executionTimeMs() != null) out.put("execution_time_ms", plan.executionTimeMs());

        List<PlanNode> all = new ArrayList<>();
        flatten(plan.root(), all);
        out.put("node_count", all.size());

        PlanNode root = plan.root();
        if (root != null) {
            Map<String, Object> rootSummary = new LinkedHashMap<>();
            rootSummary.put("node_type", root.nodeType());
            if (root.totalCost() != null)     rootSummary.put("total_cost", root.totalCost());
            if (root.estimatedRows() != null) rootSummary.put("estimated_rows", root.estimatedRows());
            if (root.actualRows() != null)    rootSummary.put("actual_rows", root.actualRowsTotal());
            if (root.actualTotalTimeMs() != null) rootSummary.put("actual_total_time_ms", root.actualTotalTimeMs());
            out.put("root", rootSummary);
        }

        out.put("top_expensive_nodes", topExpensive(all, 3, plan.analyzed()));
        out.put("full_scans",          fullScans(all));
        out.put("estimation_errors",   estimationErrors(all, plan.analyzed()));
        out.put("risky_nested_loops",  riskyNestedLoops(all));
        out.put("disk_spills",         diskSpills(all));
        return out;
    }

    // ---------------- rules ----------------

    private static List<Map<String, Object>> topExpensive(List<PlanNode> nodes, int n, boolean analyzed) {
        Comparator<PlanNode> cmp;
        String metric;
        if (analyzed) {
            // With ANALYZE, "own" time would be even better, but total-inclusive already surfaces
            // the heaviest subtree — good enough for a short summary.
            cmp = Comparator.comparingDouble(p -> orZero(p.actualTotalTimeMs()).doubleValue());
            metric = "actual_total_time_ms";
        } else {
            cmp = Comparator.comparingDouble(p -> orZero(p.totalCost()).doubleValue());
            metric = "total_cost";
        }
        return nodes.stream()
                .sorted(cmp.reversed())
                .limit(n)
                .map(p -> describeNode(p, metric))
                .toList();
    }

    private static List<Map<String, Object>> fullScans(List<PlanNode> nodes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            if (!isFullScan(p)) continue;
            long rows = orZero(p.estimatedRows()).longValue();
            if (rows < FULL_SCAN_BIG_ROWS && orZero(p.actualRowsTotal()).longValue() < FULL_SCAN_BIG_ROWS) {
                continue;
            }
            Map<String, Object> entry = describeNode(p, null);
            entry.put("reason", "large full scan (" + rows + " estimated rows)");
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> estimationErrors(List<PlanNode> nodes, boolean analyzed) {
        if (!analyzed) return List.of(); // can't compare estimate vs reality without ANALYZE
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            Long est = p.estimatedRows();
            Long act = p.actualRowsTotal();
            if (est == null || act == null) continue;
            if (est == 0 && act == 0) continue;
            double ratio = (Math.max(est, act) + 1.0) / (Math.min(est, act) + 1.0);
            if (ratio < EST_ERROR_FACTOR) continue;
            Map<String, Object> entry = describeNode(p, null);
            entry.put("estimated_rows", est);
            entry.put("actual_rows", act);
            entry.put("ratio", round(ratio, 1));
            entry.put("reason", est < act
                    ? "optimizer underestimated — stats may be stale or predicate is correlated"
                    : "optimizer overestimated — filter removed most rows");
            out.add(entry);
        }
        // Sort by ratio, largest first.
        out.sort((a, b) -> Double.compare(toDouble(b.get("ratio")), toDouble(a.get("ratio"))));
        return out;
    }

    private static List<Map<String, Object>> riskyNestedLoops(List<PlanNode> nodes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            if (!"Nested Loop".equalsIgnoreCase(p.nodeType())
                    && !"NESTED LOOPS".equalsIgnoreCase(p.nodeType())) continue;
            if (p.children().isEmpty()) continue;
            PlanNode outer = p.children().get(0);
            long outerRows = orZero(outer.actualRowsTotal() != null
                    ? outer.actualRowsTotal() : outer.estimatedRows()).longValue();
            if (outerRows < NESTED_LOOP_BIG_OUTER_ROWS) continue;
            Map<String, Object> entry = describeNode(p, null);
            entry.put("outer_node_type", outer.nodeType());
            entry.put("outer_rows", outerRows);
            entry.put("reason", "Nested Loop with " + outerRows + " outer rows — " +
                    "each outer row probes the inner side; hash/merge join may be cheaper");
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> diskSpills(List<PlanNode> nodes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            Map<String, Object> raw = p.raw();
            if (raw == null) continue;
            String sortMethod = firstString(raw, "Sort Method", "sort_method");
            if (sortMethod != null && sortMethod.toLowerCase(Locale.ROOT).contains("external")) {
                Map<String, Object> e = describeNode(p, null);
                e.put("sort_method", sortMethod);
                e.put("sort_space_kb", raw.get("Sort Space Used"));
                e.put("reason", "Sort spilled to disk (Sort Method: " + sortMethod + ") — " +
                        "consider raising work_mem or reducing sort input");
                out.add(e);
                continue;
            }
            String sortSpaceType = firstString(raw, "Sort Space Type", "sort_space_type");
            if ("Disk".equalsIgnoreCase(sortSpaceType)) {
                Map<String, Object> e = describeNode(p, null);
                e.put("sort_space_type", sortSpaceType);
                e.put("reason", "Sort used disk — work_mem exhausted");
                out.add(e);
            }
        }
        return out;
    }

    // ---------------- helpers ----------------

    private static boolean isFullScan(PlanNode p) {
        String t = p.nodeType();
        if (t == null) return false;
        String u = t.toUpperCase(Locale.ROOT);
        // PostgreSQL: "Seq Scan"; Oracle: "TABLE ACCESS FULL" (full unpartitioned) or
        // "TABLE ACCESS STORAGE FULL" (Exadata). Exclude partition-pruned variants.
        return "SEQ SCAN".equals(u)
                || u.equals("TABLE ACCESS FULL")
                || u.equals("TABLE ACCESS STORAGE FULL")
                || u.startsWith("PARTITION RANGE")
                    && u.contains("FULL");
    }

    private static Map<String, Object> describeNode(PlanNode p, String highlightMetric) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_type", p.nodeType());
        if (p.relation() != null) m.put("relation", p.relation());
        if (p.totalCost() != null) m.put("total_cost", p.totalCost());
        if (p.estimatedRows() != null) m.put("estimated_rows", p.estimatedRows());
        if (p.actualRowsTotal() != null) m.put("actual_rows", p.actualRowsTotal());
        if (p.actualTotalTimeMs() != null) m.put("actual_total_time_ms", p.actualTotalTimeMs());
        if (highlightMetric != null) m.put("ranked_by", highlightMetric);
        return m;
    }

    private static void flatten(PlanNode node, List<PlanNode> out) {
        if (node == null) return;
        out.add(node);
        for (PlanNode c : node.children()) flatten(c, out);
    }

    private static Number orZero(Number n) { return n == null ? 0 : n; }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0.0;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0.0; }
    }

    private static String firstString(Map<String, Object> raw, String... keys) {
        for (String k : keys) {
            Object v = raw.get(k);
            if (v != null) return v.toString();
        }
        return null;
    }
}
