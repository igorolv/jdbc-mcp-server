package ru.it_spectrum.ai.jdbc.mcp.sql;

import net.sf.jsqlparser.JSQLParserException;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.model.query.CheckedTableSummary;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryColumnRef;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryInspection;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryLintResult;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryWarning;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.FkIndexCoverage;

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

    public QueryLintResult lint(String sql, String schema) throws SQLException {
        QueryInspection inspection = analysis.inspect(sql);

        QueryAnalysisService.QueryModel model;
        try {
            model = analysis.model(sql);
        } catch (JSQLParserException | RuntimeException e) {
            return QueryLintResult.parserError(
                    inspection,
                    warning("parser_error",
                            "JSqlParser could not parse the SQL, so metadata lint was skipped: " + rootMessage(e)));
        }

        Map<String, TableInfo> tables = loadTables(model, schema);
        List<QueryWarning> warnings = new ArrayList<>(model.warnings);

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

        return new QueryLintResult(
                inspection,
                true,
                tableSummaries(tables.values()),
                warnings.size(),
                dedupeWarnings(warnings));
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
                             List<QueryWarning> warnings) {
        for (QueryColumnRef column : model.columns) {
            String name = str(column.name());
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
                             List<QueryWarning> warnings) {
        for (QueryColumnRef column : model.columns) {
            String context = str(column.context());
            if (!"where".equals(context) && !"having".equals(context) && !"order_by".equals(context)) continue;
            String name = str(column.name());
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
                                List<QueryWarning> warnings) throws SQLException {
        Set<String> scanned = new LinkedHashSet<>();
        for (String tableName : model.physicalTableNames()) {
            if (!scanned.add(norm(tableName))) continue;
            var coverage = stats.fkIndexCoverage(schema, tableName);
            List<?> rows = coverage.uncovered();
            for (Object item : rows) {
                if (!(item instanceof FkIndexCoverage.UncoveredEntry entry)) continue;
                Object fkName = entry.fkName();
                Object cols = entry.fkColumns();
                warnings.add(warning("fk_without_supporting_index",
                        "Foreign key " + fkName + " on table '" + tableName +
                                "' has no supporting index on child columns " + cols + "."));
            }
        }
    }

    private TableInfo resolveColumnTable(QueryColumnRef column, QueryAnalysisService.QueryModel model,
                                         Map<String, TableInfo> tables) {
        String qualifier = str(column.qualifier());
        if (qualifier != null) {
            String target = model.aliases.get(qualifier);
            if (target == null) target = qualifier;
            String tableName = lastPart(target);
            return tables.get(norm(tableName));
        }
        if (tables.size() == 1) return tables.values().iterator().next();
        return null;
    }

    private List<CheckedTableSummary> tableSummaries(Collection<TableInfo> tables) {
        List<CheckedTableSummary> out = new ArrayList<>();
        for (TableInfo table : tables) {
            out.add(new CheckedTableSummary(
                    table.schema,
                    table.name,
                    table.exists(),
                    table.columns.size(),
                    table.indexes.size()));
        }
        return out;
    }

    private static List<QueryWarning> dedupeWarnings(List<QueryWarning> warnings) {
        Set<String> seen = new LinkedHashSet<>();
        List<QueryWarning> out = new ArrayList<>();
        for (QueryWarning warning : warnings) {
            String key = str(warning.code()) + "|" + str(warning.message());
            if (seen.add(key)) out.add(warning);
        }
        return out;
    }

    private static String columnText(QueryColumnRef column) {
        return column.text() == null ? String.valueOf(column.name()) : column.text();
    }

    private static QueryWarning warning(String code, String message) {
        return new QueryWarning(code, message);
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
                if (!index.isEmpty() && norm(index.getFirst()).equals(c)) return true;
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
