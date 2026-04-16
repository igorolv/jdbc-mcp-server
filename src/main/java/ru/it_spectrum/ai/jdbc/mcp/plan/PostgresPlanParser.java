package ru.it_spectrum.ai.jdbc.mcp.plan;

import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses PostgreSQL {@code EXPLAIN (FORMAT JSON [, ANALYZE, VERBOSE, BUFFERS, SETTINGS])}
 * output. The server always returns a single-column, single-row result whose value is a
 * JSON array wrapping exactly one plan object.
 */
public final class PostgresPlanParser implements PlanParser {

    @Override
    public ParsedPlan parse(QueryResult result, boolean analyzed) {
        if (result.rows().isEmpty() || result.columns().isEmpty()) {
            throw new IllegalArgumentException("Empty EXPLAIN result");
        }
        Object raw = result.rows().get(0).get(result.columns().get(0));
        String json = raw == null ? "" : raw.toString();
        Object parsed = JsonReader.parse(json);
        if (!(parsed instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Unexpected EXPLAIN JSON shape: " + json);
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("Unexpected EXPLAIN JSON top-level element");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> topMap = (Map<String, Object>) top;
        Object planObj = topMap.get("Plan");
        if (!(planObj instanceof Map<?, ?> planMap)) {
            throw new IllegalArgumentException("EXPLAIN JSON missing 'Plan' node");
        }
        @SuppressWarnings("unchecked")
        PlanNode root = toNode((Map<String, Object>) planMap);

        Map<String, Object> meta = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : topMap.entrySet()) {
            if (!"Plan".equals(e.getKey())) meta.put(e.getKey(), e.getValue());
        }
        return new ParsedPlan(
                "postgresql",
                root,
                analyzed,
                asDouble(topMap.get("Planning Time")),
                asDouble(topMap.get("Execution Time")),
                meta);
    }

    @SuppressWarnings("unchecked")
    private PlanNode toNode(Map<String, Object> node) {
        String nodeType = asString(node.get("Node Type"));
        String relation = firstNonNull(
                asString(node.get("Relation Name")),
                asString(node.get("Index Name")),
                asString(node.get("Subplan Name")),
                asString(node.get("CTE Name")));
        Double totalCost   = asDouble(node.get("Total Cost"));
        Double startupCost = asDouble(node.get("Startup Cost"));
        Long   estRows     = asLong(node.get("Plan Rows"));
        Long   actRows     = asLong(node.get("Actual Rows"));
        Long   loops       = asLong(node.get("Actual Loops"));
        Double actTime     = asDouble(node.get("Actual Total Time"));

        List<PlanNode> kids = new ArrayList<>();
        Object childList = node.get("Plans");
        if (childList instanceof List<?> cl) {
            for (Object c : cl) {
                if (c instanceof Map<?, ?> cm) {
                    kids.add(toNode((Map<String, Object>) cm));
                }
            }
        }
        return new PlanNode(nodeType, relation,
                totalCost, startupCost, estRows, actRows, loops, actTime,
                node, kids);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            // Some fields (e.g. very small rows estimates) come back as doubles; round.
            try {
                return (long) Math.round(Double.parseDouble(v.toString()));
            } catch (NumberFormatException e2) {
                return null;
            }
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
