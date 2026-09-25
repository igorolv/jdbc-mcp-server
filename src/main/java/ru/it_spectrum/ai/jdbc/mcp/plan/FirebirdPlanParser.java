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
 * Parses the explained plan Jaybird returns for a prepared statement on Firebird 3+:
 *
 * <pre>
 * Select Expression
 *     -&gt; Nested Loop Join (inner)
 *         -&gt; Table "FIAS_ADDROBJ" as "A" Full Scan
 *         -&gt; Filter
 *             -&gt; Table "FIAS_HOUSE" as "H" Access By ID
 *                 -&gt; Bitmap
 *                     -&gt; Index "I_FIAS_HOUSE_AOGUID" Range Scan (full match)
 * </pre>
 *
 * <p>Nesting is four spaces per level. Firebird reports neither costs nor row estimates, so those
 * stay {@code null}; table access nodes get the relation and the access method as node type
 * ({@code Full Scan}, {@code Access By ID}), index nodes become {@code Index Range Scan} etc.
 * Several top-level expressions (a query plus its subqueries) hang under a synthetic
 * {@code Plan} root. A legacy one-line {@code PLAN (...)} becomes a single node.
 *
 * <p>Input: the rows of one text column, the whole plan in the first row.
 */
public final class FirebirdPlanParser implements PlanParser {

    private static final Pattern TABLE = Pattern.compile("^Table \"([^\"]+)\"(?: as \"([^\"]+)\")? (.+)$");
    private static final Pattern INDEX = Pattern.compile("^Index \"([^\"]+)\" (.+)$");
    private static final int INDENT = 4;

    @Override
    public ParsedPlan parse(QueryResult result, boolean analyzed) {
        String text = planText(result);
        List<PlanNode> roots = new ArrayList<>();
        Deque<Level> stack = new ArrayDeque<>();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            int indent = leadingSpaces(line);
            String content = line.strip();
            if (content.startsWith("->")) {
                content = content.substring(2).strip();
            }
            int depth = indent / INDENT;
            PlanNode node = node(content);
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
        PlanNode root = switch (roots.size()) {
            case 0 -> null;
            case 1 -> roots.getFirst();
            default -> new PlanNode("Plan", null, null, null, null, null, null, null,
                    new LinkedHashMap<>(), roots);
        };
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("plan_text", text);
        return new ParsedPlan("firebird", root, false, null, null, meta);
    }

    private record Level(int depth, PlanNode node) {}

    private static PlanNode node(String content) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("text", content);
        String nodeType = content;
        String relation = null;
        Matcher table = TABLE.matcher(content);
        Matcher index = INDEX.matcher(content);
        if (table.matches()) {
            relation = table.group(1);
            if (table.group(2) != null) raw.put("alias", table.group(2));
            nodeType = table.group(3);
        } else if (index.matches()) {
            raw.put("index", index.group(1));
            nodeType = "Index " + index.group(2);
        }
        return new PlanNode(nodeType, relation, null, null, null, null, null, null, raw, new ArrayList<>());
    }

    private static String planText(QueryResult result) {
        if (result == null || result.rows() == null || result.rows().isEmpty()) {
            throw new IllegalArgumentException("Firebird plan is empty");
        }
        Object value = result.rows().getFirst().values().iterator().next();
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Firebird plan is empty");
        }
        return value.toString();
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }
}
