package ru.it_spectrum.ai.jdbc.mcp.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonMapperFactory;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link QueryResult} as JSON, Markdown table, or CSV.
 */
public final class ResultFormatter {

    private static final ObjectMapper JSON = JsonMapperFactory.create();

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
        try {
            return JSON.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize query result", e);
        }
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
