package ru.it_spectrum.ai.jdbc.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * Tiny pretty-printing JSON writer used by all MCP tools that return hierarchical (object) results.
 *
 * <p>Tabular results from {@code QueryResult} go through {@code ResultFormatter} instead — that path
 * is optimised for row arrays and preserves column order.
 *
 * <p>Supported types: {@code null}, {@link Map}, {@link List}, {@link Boolean}, {@link Number},
 * everything else is serialised via {@code toString()} as a JSON string.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('\n').append("  ".repeat(depth + 1));
                appendString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                append(sb, e.getValue(), depth + 1);
            }
            if (!first) sb.append('\n').append("  ".repeat(depth));
            sb.append('}');
            return;
        }
        if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                append(sb, list.get(i), depth + 1);
            }
            sb.append(']');
            return;
        }
        if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
            return;
        }
        appendString(sb, v.toString());
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
