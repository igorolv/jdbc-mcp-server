package ru.it_spectrum.ai.jdbc.mcp.plan;

import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses Oracle {@code PLAN_TABLE} rows into a {@link ParsedPlan}. The input is a flat
 * row set ordered by {@code ID} (the natural pre-order traversal that Oracle populates); we
 * reassemble the parent/child tree via {@code PARENT_ID} and carry through the useful
 * cost / cardinality / predicate columns in {@link PlanNode#raw()}.
 *
 * <p>Oracle's EXPLAIN is always static — no actual rows / times — so {@code analyzed} is always
 * {@code false} and the corresponding {@link PlanNode} fields are {@code null}.
 */
public final class OraclePlanParser implements PlanParser {

    @Override
    public ParsedPlan parse(QueryResult result, boolean analyzed) {
        Map<Long, PlanNode> byId   = new HashMap<>();
        Map<Long, Long>     parent = new HashMap<>();
        Map<Long, Map<String, Object>> payloads = new LinkedHashMap<>();

        for (Map<String, Object> row : result.rows()) {
            Long id       = asLong(row.get("ID"));
            Long parentId = asLong(row.get("PARENT_ID"));
            if (id == null) continue;
            parent.put(id, parentId);
            payloads.put(id, caseInsensitive(row));
        }

        // Build nodes first (without children) so we can wire parents/children afterwards.
        for (Map.Entry<Long, Map<String, Object>> e : payloads.entrySet()) {
            Long id = e.getKey();
            Map<String, Object> p = e.getValue();
            String operation = asString(p.get("operation"));
            String options   = asString(p.get("options"));
            String nodeType  = operation == null ? "UNKNOWN"
                    : (options == null || options.isBlank() ? operation : operation + " " + options);
            String relation  = asString(p.get("object_name"));
            Double cost      = asDouble(p.get("cost"));
            Long   estRows   = asLong(p.get("cardinality"));
            byId.put(id, new PlanNode(
                    nodeType,
                    relation,
                    cost,
                    null,       // Oracle has no "startup cost" concept
                    estRows,
                    null,       // actual rows (only from DBMS_XPLAN with GATHER_PLAN_STATISTICS)
                    null,       // loops
                    null,       // actual time
                    p,
                    new ArrayList<>()));
        }

        PlanNode root = null;
        for (Map.Entry<Long, Long> e : parent.entrySet()) {
            PlanNode node = byId.get(e.getKey());
            Long pid = e.getValue();
            if (pid == null) {
                // Oracle's "SELECT STATEMENT" row (id 0) has NULL parent_id — that's the root.
                if (root == null) root = node;
                continue;
            }
            PlanNode p = byId.get(pid);
            if (p != null) p.children().add(node);
        }
        if (root == null && !byId.isEmpty()) {
            // Fallback: first id is the root.
            root = byId.values().iterator().next();
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rows", result.rows().size());
        return new ParsedPlan("oracle", root, false, null, null, meta);
    }

    private static Map<String, Object> caseInsensitive(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row.size() * 2);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            out.put(k.toLowerCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) {
            try { return (long) Math.round(Double.parseDouble(v.toString())); }
            catch (NumberFormatException e2) { return null; }
        }
    }

    // Expose helpers so PlanAnalyzer can pull Oracle-only fields (filter predicates etc).
    static String filterPredicate(PlanNode node) {
        return node.raw() == null ? null : asString(node.raw().get("filter_predicates"));
    }

    static String accessPredicate(PlanNode node) {
        return node.raw() == null ? null : asString(node.raw().get("access_predicates"));
    }
}
