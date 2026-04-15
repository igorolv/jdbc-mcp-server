package ru.it_spectrum.ai.jdbc.mcp.format;

import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link QueryResult} as JSON, Markdown table, or CSV.
 *
 * <p>Implementation is intentionally dependency-free (no Jackson) so that the output is
 * deterministic and we can guarantee LLM-friendly stable formatting.
 */
public final class ResultFormatter {

    private ResultFormatter() {
    }

    public static String format(QueryResult result, OutputFormat format) {
        return switch (format) {
            case JSON -> toJson(result);
            case MARKDOWN -> toMarkdown(result);
            case CSV -> toCsv(result);
        };
    }

    // ---------------- JSON ----------------

    public static String toJson(QueryResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"columns\": ");
        appendJsonStringArray(sb, result.columns());
        sb.append(",\n  \"columnTypes\": ");
        appendJsonStringArray(sb, result.columnTypes());
        sb.append(",\n  \"rowCount\": ").append(result.rowCount());
        sb.append(",\n  \"truncated\": ").append(result.truncated());
        sb.append(",\n  \"rows\": [");
        List<Map<String, Object>> rows = result.rows();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\n    ");
            appendJsonObject(sb, rows.get(i));
        }
        if (!rows.isEmpty()) sb.append("\n  ");
        sb.append("]\n}");
        return sb.toString();
    }

    private static void appendJsonStringArray(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            appendJsonString(sb, values.get(i));
        }
        sb.append(']');
    }

    private static void appendJsonObject(StringBuilder sb, Map<String, Object> obj) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : obj.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            appendJsonString(sb, e.getKey());
            sb.append(": ");
            appendJsonValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendJsonValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof Number || v instanceof Boolean) {
            sb.append(v.toString());
            return;
        }
        appendJsonString(sb, v.toString());
    }

    private static void appendJsonString(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------------- Markdown ----------------

    public static String toMarkdown(QueryResult result) {
        List<String> columns = result.columns();
        if (columns.isEmpty()) {
            return "_(no columns)_\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", columns.stream().map(ResultFormatter::escapeMd).toList())).append(" |\n");
        sb.append("|");
        for (int i = 0; i < columns.size(); i++) sb.append("---|");
        sb.append('\n');
        for (Map<String, Object> row : result.rows()) {
            sb.append('|');
            for (String col : columns) {
                Object v = row.get(col);
                sb.append(' ').append(escapeMd(v == null ? "" : v.toString())).append(" |");
            }
            sb.append('\n');
        }
        sb.append("\n_rows: ").append(result.rowCount());
        if (result.truncated()) sb.append(" (truncated)");
        sb.append('_');
        return sb.toString();
    }

    private static String escapeMd(String s) {
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    // ---------------- CSV ----------------

    public static String toCsv(QueryResult result) {
        StringBuilder sb = new StringBuilder();
        List<String> columns = result.columns();
        List<String> header = new ArrayList<>(columns.size());
        for (String c : columns) header.add(csv(c));
        sb.append(String.join(",", header)).append('\n');
        for (Map<String, Object> row : result.rows()) {
            List<String> cells = new ArrayList<>(columns.size());
            for (String col : columns) {
                Object v = row.get(col);
                cells.add(csv(v == null ? "" : v.toString()));
            }
            sb.append(String.join(",", cells)).append('\n');
        }
        if (result.truncated()) {
            sb.append("# truncated at ").append(result.rowCount()).append(" rows\n");
        }
        return sb.toString();
    }

    private static String csv(String s) {
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String escaped = s.replace("\"", "\"\"");
        return needQuote ? "\"" + escaped + "\"" : escaped;
    }
}
