package ru.it_spectrum.ai.jdbc.mcp.sql;

import net.sf.jsqlparser.JSQLParserException;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

/**
 * Metadata-aware SQL linting for LLM query authoring. Findings are advisory and never block
 * execution; the database driver remains the syntax/object validator.
 */
@Service
public class QueryLintService {

    private final QueryAnalysisService analysis;
    private final MetadataService metadata;
    private final StatsService stats;

    public QueryLintService(QueryAnalysisService analysis, MetadataService metadata, StatsService stats) {
        this.analysis = analysis;
        this.metadata = metadata;
        this.stats = stats;
    }

    public Map<String, Object> lint(String sql, String schema) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inspection", analysis.inspect(sql));

        QueryAnalysisService.QueryModel model;
        try {
            model = analysis.model(sql);
        } catch (JSQLParserException | RuntimeException e) {
            out.put("lintable", false);
            out.put("warnings", List.of(warning("parser_error",
                    "JSqlParser could not parse the SQL, so metadata lint was skipped: " + rootMessage(e))));
            return out;
        }

        Map<String, TableInfo> tables = loadTables(model, schema);
        List<Map<String, Object>> warnings = new ArrayList<>();
        warnings.addAll(model.warnings);

        if (model.hasSelectStar) {
            warnings.add(warning("select_star",
                    "SELECT * makes result shape and payload size less predictable for LLM consumption."));
        }
        if (model.hasSelectInto) {
            warnings.add(warning("select_into",
                    "SELECT INTO is not read-only and should be rejected by the read-only guard."));
        }
        if (model.hasForUpdate) {
            warnings.add(warning("for_update",
                    "FOR UPDATE locks rows and should be rejected by the read-only guard."));
        }

        for (TableInfo table : tables.values()) {
            if (!table.exists()) {
                warnings.add(warning("unknown_table",
                        "Metadata lookup found no columns for table '" + table.name + "'."));
            }
        }

        lintColumns(model, tables, warnings);
        lintIndexes(model, tables, warnings);
        lintFkCoverage(model, schema, warnings);

        out.put("lintable", true);
        out.put("tablesChecked", tableSummaries(tables.values()));
        out.put("warningCount", warnings.size());
        out.put("warnings", dedupeWarnings(warnings));
        return out;
    }

    private Map<String, TableInfo> loadTables(QueryAnalysisService.QueryModel model, String schema)
            throws SQLException {
        Map<String, TableInfo> tables = new LinkedHashMap<>();
        for (String tableName : model.physicalTableNames()) {
            TableDescription desc = metadata.describeTable(schema, tableName);
            TableInfo info = new TableInfo(schema, tableName, desc);
            tables.put(norm(tableName), info);
        }
        return tables;
    }

    private void lintColumns(QueryAnalysisService.QueryModel model, Map<String, TableInfo> tables,
                             List<Map<String, Object>> warnings) {
        for (Map<String, Object> column : model.columns) {
            String name = str(column.get("name"));
            if (name == null || "*".equals(name)) continue;
            TableInfo table = resolveColumnTable(column, model, tables);
            if (table == null || !table.exists()) continue;
            if (!table.hasColumn(name)) {
                warnings.add(warning("unknown_column",
                        "Column '" + columnText(column) + "' was not found in metadata for table '" + table.name + "'."));
            }
        }
    }

    private void lintIndexes(QueryAnalysisService.QueryModel model, Map<String, TableInfo> tables,
                             List<Map<String, Object>> warnings) {
        for (Map<String, Object> column : model.columns) {
            String context = str(column.get("context"));
            if (!"where".equals(context) && !"having".equals(context) && !"order_by".equals(context)) continue;
            String name = str(column.get("name"));
            if (name == null) continue;
            TableInfo table = resolveColumnTable(column, model, tables);
            if (table == null || !table.exists() || !table.hasColumn(name)) continue;
            if (!table.hasLeadingIndex(name)) {
                String code = "order_by".equals(context) ? "order_by_not_leading_index" : "predicate_not_leading_index";
                warnings.add(warning(code,
                        "Column '" + table.name + "." + name + "' is used in " + context +
                                " but is not the leading column of any visible index."));
            }
        }
    }

    private void lintFkCoverage(QueryAnalysisService.QueryModel model, String schema,
                                List<Map<String, Object>> warnings) throws SQLException {
        Set<String> scanned = new LinkedHashSet<>();
        for (String tableName : model.physicalTableNames()) {
            if (!scanned.add(norm(tableName))) continue;
            Map<String, Object> coverage = stats.fkIndexCoverage(schema, tableName);
            Object uncovered = coverage.get("uncovered");
            if (!(uncovered instanceof List<?> rows)) continue;
            for (Object item : rows) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                Object fkName = raw.get("fk_name");
                Object cols = raw.get("fk_columns");
                warnings.add(warning("fk_without_supporting_index",
                        "Foreign key " + fkName + " on table '" + tableName +
                                "' has no supporting index on child columns " + cols + "."));
            }
        }
    }

    private TableInfo resolveColumnTable(Map<String, Object> column, QueryAnalysisService.QueryModel model,
                                         Map<String, TableInfo> tables) {
        String qualifier = str(column.get("qualifier"));
        if (qualifier != null) {
            String target = model.aliases.get(qualifier);
            if (target == null) target = qualifier;
            String tableName = lastPart(target);
            return tables.get(norm(tableName));
        }
        if (tables.size() == 1) return tables.values().iterator().next();
        return null;
    }

    private List<Map<String, Object>> tableSummaries(Collection<TableInfo> tables) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TableInfo table : tables) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schema", table.schema);
            row.put("table", table.name);
            row.put("exists", table.exists());
            row.put("columnCount", table.columns.size());
            row.put("indexCount", table.indexes.size());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> dedupeWarnings(List<Map<String, Object>> warnings) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> warning : warnings) {
            String key = str(warning.get("code")) + "|" + str(warning.get("message"));
            if (seen.add(key)) out.add(warning);
        }
        return out;
    }

    private static String columnText(Map<String, Object> column) {
        Object text = column.get("text");
        return text == null ? String.valueOf(column.get("name")) : String.valueOf(text);
    }

    private static Map<String, Object> warning(String code, String message) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", code);
        w.put("message", message);
        return w;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? e.toString() : cur.getMessage();
    }

    private static String lastPart(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static String norm(String value) {
        return value == null ? "" : unquote(value).toLowerCase(Locale.ROOT);
    }

    private static String unquote(String value) {
        String s = value.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("[") && s.endsWith("]")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String str(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static final class TableInfo {
        final String schema;
        final String name;
        final Set<String> columns = new LinkedHashSet<>();
        final List<List<String>> indexes = new ArrayList<>();

        TableInfo(String schema, String name, TableDescription desc) {
            this.schema = schema;
            this.name = name;
            List<Column> cols = desc.columns();
            if (cols instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Column col && col.name() != null) {
                        columns.add(norm(col.name()));
                    }
                }
            }
            PrimaryKey pk = desc.primaryKey();
            if (pk != null) addIndexColumns(pk.columns());
            List<Index> idx = desc.indexes();
            if (idx instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Index index) addIndexColumns(index.columns());
                }
            }
        }

        boolean exists() {
            return !columns.isEmpty();
        }

        boolean hasColumn(String column) {
            return columns.contains(norm(column));
        }

        boolean hasLeadingIndex(String column) {
            String c = norm(column);
            for (List<String> index : indexes) {
                if (!index.isEmpty() && norm(index.get(0)).equals(c)) return true;
            }
            return false;
        }

        private void addIndexColumns(List<String> raw) {
            if (raw == null || raw.isEmpty()) return;
            List<String> cols = new ArrayList<>();
            for (String item : raw) {
                if (item != null) cols.add(item);
            }
            if (!cols.isEmpty()) indexes.add(cols);
        }
    }
}
