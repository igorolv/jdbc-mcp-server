package ru.it_spectrum.ai.jdbc.mcp.plan;

import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code EXPLAIN QUERY PLAN} tree as {@code SqliteDialect#driverPlan} renders it — the
 * {@code sqlite3} shell format:
 *
 * <pre>
 * QUERY PLAN
 * |--SEARCH t USING INDEX idx_name (name=?)
 * `--LIST SUBQUERY 1
 *    `--SCAN o
 * </pre>
 *
 * <p>Each level of nesting is three characters ({@code "|  "} or {@code "   "}) before the
 * {@code |--} / {@code `--} connector. Table access lines become typed nodes so the analyzer can
 * reason about them: {@code SCAN t} is a {@code TABLE SCAN}, {@code SCAN t USING [COVERING] INDEX i}
 * an {@code INDEX SCAN} — both full scans — and {@code SEARCH t ...} an {@code INDEX SEEK}. SQLite
 * reports no costs or row estimates.
 */
public final class SqlitePlanParser implements PlanParser {

    private static final Pattern LINE = Pattern.compile("^((?:[| ] {2})*)[|`]--(.*)$");
    /** {@code SCAN|SEARCH [TABLE] name [AS alias] [USING ...]}; {@code TABLE} appears before SQLite 3.36. */
    private static final Pattern ACCESS = Pattern.compile(
            "^(SCAN|SEARCH) (?:TABLE )?(\\S+)(?: AS (\\S+))?(?: (USING .*))?$");

    @Override
    public ParsedPlan parse(QueryResult result, boolean analyzed) {
        String text = planText(result);
        List<PlanNode> roots = new ArrayList<>();
        Deque<Level> stack = new ArrayDeque<>();
        for (String line : text.split("\\R")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue; // the "QUERY PLAN" header
            int depth = m.group(1).length() / 3;
            PlanNode node = node(m.group(2).strip());
            while (!stack.isEmpty() && stack.peek().depth() >= depth) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().node().children().add(node);
            }
            stack.push(new Level(depth, node));
        }
        PlanNode root = roots.size() == 1 ? roots.getFirst()
                : new PlanNode("QUERY PLAN", null, null, null, null, null, null, null,
                new LinkedHashMap<>(), roots);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("plan_text", text);
        return new ParsedPlan("sqlite", root, false, null, null, meta);
    }

    private record Level(int depth, PlanNode node) {}

    private static PlanNode node(String detail) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("detail", detail);
        String nodeType = detail;
        String relation = null;
        Matcher access = ACCESS.matcher(detail);
        if (access.matches()) {
            relation = access.group(2);
            if (access.group(3) != null) raw.put("alias", access.group(3));
            String using = access.group(4);
            if (using != null) raw.put("using", using);
            boolean index = using != null && using.contains("INDEX");
            nodeType = "SEARCH".equals(access.group(1)) ? "INDEX SEEK"
                    : index ? "INDEX SCAN" : "TABLE SCAN";
        }
        return new PlanNode(nodeType, relation, null, null, null, null, null, null, raw, new ArrayList<>());
    }

    private static String planText(QueryResult result) {
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            throw new IllegalArgumentException("SQLite plan is empty");
        }
        Object value = result.rows().getFirst().values().iterator().next();
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("SQLite plan is empty");
        }
        return value.toString();
    }
}
