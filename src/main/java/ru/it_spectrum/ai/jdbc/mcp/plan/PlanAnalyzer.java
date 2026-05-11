package ru.it_spectrum.ai.jdbc.mcp.plan;

import ru.it_spectrum.ai.jdbc.mcp.model.plan.PlanAnalysisSummary;
import ru.it_spectrum.ai.jdbc.mcp.model.plan.PlanNodeSummary;

import java.util.ArrayList;
import java.util.Comparator;
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

    public static PlanAnalysisSummary summarize(ParsedPlan plan) {
        List<PlanNode> all = new ArrayList<>();
        flatten(plan.root(), all);

        PlanNode root = plan.root();
        PlanNodeSummary rootSummary = root == null ? null : rootSummary(root);

        return new PlanAnalysisSummary(
                plan.engine(),
                plan.analyzed(),
                plan.planningTimeMs(),
                plan.executionTimeMs(),
                all.size(),
                rootSummary,
                topExpensive(all, 3, plan.analyzed()),
                fullScans(all),
                estimationErrors(all, plan.analyzed()),
                riskyNestedLoops(all),
                diskSpills(all)
        );
    }

    // ---------------- rules ----------------

    private static List<PlanNodeSummary> topExpensive(List<PlanNode> nodes, int n, boolean analyzed) {
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

    private static List<PlanNodeSummary> fullScans(List<PlanNode> nodes) {
        List<PlanNodeSummary> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            if (!isFullScan(p)) continue;
            long rows = orZero(p.estimatedRows()).longValue();
            if (rows < FULL_SCAN_BIG_ROWS && orZero(p.actualRowsTotal()).longValue() < FULL_SCAN_BIG_ROWS) {
                continue;
            }
            out.add(nodeSummary(
                    p, null,
                    "large full scan (" + rows + " estimated rows)",
                    null, null, null, null, null, null
            ));
        }
        return out;
    }

    private static List<PlanNodeSummary> estimationErrors(List<PlanNode> nodes, boolean analyzed) {
        if (!analyzed) return List.of(); // can't compare estimate vs reality without ANALYZE
        List<PlanNodeSummary> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            Long est = p.estimatedRows();
            Long act = p.actualRowsTotal();
            if (est == null || act == null) continue;
            if (est == 0 && act == 0) continue;
            double ratio = (Math.max(est, act) + 1.0) / (Math.min(est, act) + 1.0);
            if (ratio < EST_ERROR_FACTOR) continue;
            out.add(nodeSummary(
                    p, null,
                    est < act
                            ? "optimizer underestimated — stats may be stale or predicate is correlated"
                            : "optimizer overestimated — filter removed most rows",
                    round(ratio, 1), null, null, null, null, null
            ));
        }
        // Sort by ratio, largest first.
        out.sort(Comparator.comparingDouble((PlanNodeSummary n) -> orZero(n.ratio()).doubleValue()).reversed());
        return out;
    }

    private static List<PlanNodeSummary> riskyNestedLoops(List<PlanNode> nodes) {
        List<PlanNodeSummary> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            if (!"Nested Loop".equalsIgnoreCase(p.nodeType())
                    && !"NESTED LOOPS".equalsIgnoreCase(p.nodeType())
                    && !"Nested Loops".equalsIgnoreCase(p.nodeType())) continue;
            if (p.children().isEmpty()) continue;
            PlanNode outer = p.children().getFirst();
            long outerRows = orZero(outer.actualRowsTotal() != null
                    ? outer.actualRowsTotal() : outer.estimatedRows()).longValue();
            if (outerRows < NESTED_LOOP_BIG_OUTER_ROWS) continue;
            out.add(nodeSummary(
                    p, null,
                    "Nested Loop with " + outerRows + " outer rows — " +
                            "each outer row probes the inner side; hash/merge join may be cheaper",
                    null, outer.nodeType(), outerRows, null, null, null
            ));
        }
        return out;
    }

    private static List<PlanNodeSummary> diskSpills(List<PlanNode> nodes) {
        List<PlanNodeSummary> out = new ArrayList<>();
        for (PlanNode p : nodes) {
            Map<String, Object> raw = p.raw();
            if (raw == null) continue;
            String sortMethod = firstString(raw, "Sort Method", "sort_method");
            if (sortMethod != null && sortMethod.toLowerCase(Locale.ROOT).contains("external")) {
                out.add(nodeSummary(
                        p, null,
                        "Sort spilled to disk (Sort Method: " + sortMethod + ") — " +
                                "consider raising work_mem or reducing sort input",
                        null, null, null, sortMethod, raw.get("Sort Space Used"), null
                ));
                continue;
            }
            String sortSpaceType = firstString(raw, "Sort Space Type", "sort_space_type");
            if ("Disk".equalsIgnoreCase(sortSpaceType)) {
                out.add(nodeSummary(
                        p, null,
                        "Sort used disk — work_mem exhausted",
                        null, null, null, null, null, sortSpaceType
                ));
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
        // "TABLE ACCESS STORAGE FULL" (Exadata). SQL Server: table/index scan operators.
        // Exclude partition-pruned variants.
        return "SEQ SCAN".equals(u)
                || u.equals("TABLE ACCESS FULL")
                || u.equals("TABLE ACCESS STORAGE FULL")
                || u.equals("TABLE SCAN")
                || u.equals("CLUSTERED INDEX SCAN")
                || u.equals("INDEX SCAN")
                || u.equals("COLUMNSTORE INDEX SCAN")
                || u.startsWith("PARTITION RANGE")
                    && u.contains("FULL");
    }

    private static PlanNodeSummary rootSummary(PlanNode p) {
        return new PlanNodeSummary(
                p.nodeType(),
                null,
                p.totalCost(),
                p.estimatedRows(),
                p.actualRows() == null ? null : p.actualRowsTotal(),
                p.actualTotalTimeMs(),
                null, null, null, null, null, null, null, null
        );
    }

    private static PlanNodeSummary describeNode(PlanNode p, String highlightMetric) {
        return nodeSummary(p, highlightMetric, null, null, null, null, null, null, null);
    }

    private static PlanNodeSummary nodeSummary(
            PlanNode p,
            String highlightMetric,
            String reason,
            Double ratio,
            String outerNodeType,
            Long outerRows,
            String sortMethod,
            Object sortSpaceKb,
            String sortSpaceType
    ) {
        return new PlanNodeSummary(
                p.nodeType(),
                p.relation(),
                p.totalCost(),
                p.estimatedRows(),
                p.actualRowsTotal(),
                p.actualTotalTimeMs(),
                highlightMetric,
                reason,
                ratio,
                outerNodeType,
                outerRows,
                sortMethod,
                sortSpaceKb,
                sortSpaceType
        );
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

    private static String firstString(Map<String, Object> raw, String... keys) {
        for (String k : keys) {
            Object v = raw.get(k);
            if (v != null) return v.toString();
        }
        return null;
    }
}
