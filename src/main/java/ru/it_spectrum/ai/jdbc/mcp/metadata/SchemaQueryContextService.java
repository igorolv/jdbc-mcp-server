package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.QueryContext;
import ru.it_spectrum.ai.jdbc.mcp.model.context.QueryContextColumn;
import ru.it_spectrum.ai.jdbc.mcp.model.context.QueryContextConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.context.QueryContextSample;
import ru.it_spectrum.ai.jdbc.mcp.model.context.QueryContextTable;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.model.context.ShortestPath;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.CheckConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaQueryContextService extends SchemaContextSupport {

    SchemaQueryContextService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                              SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public QueryContext queryContext(String schema, String terms, String tables,
                                            Boolean includeSamples, Integer maxTables) throws SQLException {
        int tableLimit = clamp(maxTables, 12, 1, 50);
        boolean samples = Boolean.TRUE.equals(includeSamples);
        List<String> requestedTables = splitCsvInput(tables);
        List<String> tokens = queryTokens(terms);

        Map<String, TableDescription> selected = new LinkedHashMap<>();
        Map<String, SemanticTableCandidate> semanticMatchesByTable = new LinkedHashMap<>();
        if (!requestedTables.isEmpty()) {
            selected.putAll(metadata.describeTables(schema, requestedTables));
        }

        List<SemanticTableCandidate> semanticCandidates = List.of();
        if (selected.size() < tableLimit && usageCatalog != null && usageCatalog.enabled()
                && terms != null && !terms.isBlank()) {
            semanticCandidates = usageCatalog.semanticTableCandidates(schema, terms, tableLimit);

            Map<String, List<String>> candidatesBySchema = new LinkedHashMap<>();
            Map<String, SemanticTableCandidate> candidateByKey = new LinkedHashMap<>();
            for (SemanticTableCandidate c : semanticCandidates) {
                if (selected.size() >= tableLimit) break;
                String cs = c.schema() == null ? schema : c.schema();
                String ck = key(cs, c.table());
                if (selected.containsKey(ck)) continue;
                candidatesBySchema.computeIfAbsent(cs, k -> new ArrayList<>()).add(c.table());
                candidateByKey.putIfAbsent(ck, c);
            }
            for (var entry : candidatesBySchema.entrySet()) {
                Map<String, TableDescription> loaded;
                try {
                    loaded = metadata.describeTables(entry.getKey(), entry.getValue());
                } catch (SQLException ignored) {
                    continue;
                }
                for (TableDescription td : loaded.values()) {
                    if (selected.size() >= tableLimit) break;
                    String tk = key(td.schema(), td.name());
                    selected.putIfAbsent(tk, td);
                    SemanticTableCandidate c = candidateByKey.get(tk);
                    if (c != null) semanticMatchesByTable.put(tk, c);
                }
            }
        }

        if (selected.size() < tableLimit) {
            // Fill the remaining slots with discovery candidates. Critically, we must NOT call the
            // heavyweight describeTable on every table in the schema here: on large schemas that
            // fans out into hundreds of per-table metadata scans (each doing a schema-wide
            // constraint read) and stalls the whole single-threaded stdio server past the client
            // timeout. Instead we rank cheaply from the bulk table listing and only fully describe
            // the shortlist we actually keep.
            int remaining = tableLimit - selected.size();
            List<TableEntry> listed = metadata.listTables(schema, "%", parseTypes("TABLE,VIEW,MATERIALIZED VIEW"));
            List<String> fillNames = new ArrayList<>();

            if (tokens.isEmpty()) {
                // No search terms to rank by. Only auto-fill when the caller pinned nothing; when
                // explicit tables were requested, honor exactly those rather than scanning the
                // schema for arbitrary additions.
                if (requestedTables.isEmpty()) {
                    for (TableEntry row : listed) {
                        if (fillNames.size() >= remaining) break;
                        String name = row.name();
                        if (name == null || name.isBlank()) continue;
                        if (selected.containsKey(key(row.schema(), name))) continue;
                        fillNames.add(name);
                    }
                }
            } else {
                List<TableEntry> ranked = new ArrayList<>();
                for (TableEntry row : listed) {
                    String name = row.name();
                    if (name == null || name.isBlank()) continue;
                    if (selected.containsKey(key(row.schema(), name))) continue;
                    if (entryRelevanceScore(row, tokens) > 0) ranked.add(row);
                }
                ranked.sort((a, b) -> Integer.compare(
                        entryRelevanceScore(b, tokens), entryRelevanceScore(a, tokens)));
                for (TableEntry row : ranked) {
                    if (fillNames.size() >= remaining) break;
                    fillNames.add(row.name());
                }
            }

            if (!fillNames.isEmpty()) {
                Map<String, TableDescription> filled = metadata.describeTables(schema, fillNames);
                for (TableDescription td : filled.values()) {
                    if (selected.size() >= tableLimit) break;
                    selected.putIfAbsent(key(td.schema(), td.name()), td);
                }
            }
        }

        List<RelationshipEdge> declaredEdges = new ArrayList<>();
        for (TableDescription info : selected.values()) declaredEdges.addAll(outgoingEdges(info));

        List<QueryContextTable> tableContexts = new ArrayList<>();
        for (TableDescription info : selected.values()) {
            tableContexts.add(queryTableContext(info, tokens, samples,
                    semanticMatchesByTable.get(key(info.schema(), info.name()))));
        }

        return new QueryContext(schema, terms, requestedTables, samples,
                tableContexts.size(),
                semanticCandidates,
                tableContexts,
                graphEdges(declaredEdges),
                pairwiseJoinPaths(new ArrayList<>(selected.keySet()), declaredEdges));
    }

    private QueryContextTable queryTableContext(TableDescription info, List<String> tokens,
                                                boolean includeSamples,
                                                SemanticTableCandidate semanticMatch) {
        String schema = info.schema();
        String table = info.name();
        Map<String, List<String>> allowedValues = allowedValues(info);
        Map<String, TableDegree> degrees = new HashMap<>();
        degrees.put(key(schema, table), new TableDegree());
        return new QueryContextTable(
                schema,
                table,
                info.type(),
                classifyTable(info, degrees),
                isBlank(info.remarks()) ? null : info.remarks(),
                info.primaryKey(),
                allowedValues,
                relevantColumns(info, tokens),
                compactCheckConstraints(info),
                info.foreignKeys(),
                compactIndexes(info.indexes()),
                semanticMatch,
                includeSamples ? sampleRowsBestEffort(schema, table, 3) : null);
    }

    private List<QueryContextColumn> relevantColumns(TableDescription info, List<String> tokens) {
        List<QueryContextColumn> columns = new ArrayList<>();
        Map<String, List<String>> allowedValues = allowedValues(info);
        Set<String> pk = info.primaryKey() == null
                ? Set.of()
                : new HashSet<>(info.primaryKey().columns());
        Set<String> fk = new HashSet<>();
        for (ForeignKey foreignKey : info.foreignKeys()) {
            fk.addAll(foreignKey.columns());
        }
        for (Column column : info.columns()) {
            String name = str(column.name());
            boolean tokenMatch = matchesAnyToken(name, tokens) || matchesAnyToken(str(column.remarks()), tokens);
            boolean important = pk.contains(name) || fk.contains(name) || tokenMatch
                    || allowedValues.containsKey(name);
            if (!important && !tokens.isEmpty()) continue;
            columns.add(new QueryContextColumn(
                    name,
                    column.typeName(),
                    column.nullable(),
                    pk.contains(name) ? Boolean.TRUE : null,
                    fk.contains(name) ? Boolean.TRUE : null,
                    allowedValues.get(name)));
        }
        if (!columns.isEmpty() || tokens.isEmpty()) return columns;
        for (Column column : info.columns()) {
            if (columns.size() >= 8) break;
            columns.add(new QueryContextColumn(
                    column.name(),
                    column.typeName(),
                    column.nullable(),
                    null,
                    null,
                    null));
        }
        return columns;
    }

    private List<QueryContextConstraint> compactCheckConstraints(TableDescription info) {
        List<QueryContextConstraint> out = new ArrayList<>();
        if (info.checkConstraints() == null) return out;
        for (CheckConstraint constraint : info.checkConstraints()) {
            out.add(new QueryContextConstraint(
                    constraint.name(),
                    "CHECK",
                    constraint.definition(),
                    singleColumn(constraint),
                    constraint.allowedValues()));
        }
        return out;
    }

    /** The lone referenced column when a check constrains exactly one column, else null. */
    private static String singleColumn(CheckConstraint constraint) {
        List<String> columns = constraint.columns();
        return columns != null && columns.size() == 1 ? columns.get(0) : null;
    }

    private QueryContextSample sampleRowsBestEffort(String schema, String table, int limit) {
        try {
            String sql = dialect.limitQuery("SELECT * FROM " + qualify(schema, table), limit);
            QueryResult result = executor.queryInternal(sql, List.of(), limit);
            return new QueryContextSample(result.columns(), result.rows(), result.rowCount(), null);
        } catch (Exception e) {
            return new QueryContextSample(null, null, null, e.getMessage());
        }
    }

    private Map<String, List<String>> allowedValues(TableDescription info) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (info.checkConstraints() == null) return out;
        for (CheckConstraint constraint : info.checkConstraints()) {
            List<String> values = constraint.allowedValues();
            String column = singleColumn(constraint);
            if (column != null && values != null && !values.isEmpty()) {
                out.put(column, values);
            }
        }
        return out;
    }

    private List<ShortestPath> pairwiseJoinPaths(List<String> tableKeys, List<RelationshipEdge> edges) {
        List<ShortestPath> paths = new ArrayList<>();
        for (int i = 0; i < tableKeys.size(); i++) {
            for (int j = i + 1; j < tableKeys.size(); j++) {
                ShortestPath path = shortestGraphPath(tableKeys.get(i), tableKeys.get(j), edges, MAX_DEPTH);
                if (path.found()) paths.add(path);
            }
        }
        return paths;
    }

    /**
     * Lightweight relevance score for discovery ranking, computed from the bulk table listing
     * (name + remarks) only — deliberately without loading columns, so ranking the whole schema
     * stays cheap. Column-level term matching for the tables we keep still happens later via the
     * full describe in {@link #relevantColumns}. Keyword discovery that needs column signals is
     * served by the usage-catalog semantic path; this brute-listing fallback ranks on the table
     * name and comment, which are the dominant signals anyway.
     */
    private int entryRelevanceScore(TableEntry entry, List<String> tokens) {
        if (tokens.isEmpty()) return 1;
        int score = 0;
        String tableName = entry.name();
        String remarks = str(entry.remarks());
        for (String token : tokens) {
            if (containsNormalized(tableName, token)) score += 10;
            if (containsNormalized(remarks, token)) score += 5;
        }
        return score;
    }

    private List<String> queryTokens(String terms) {
        List<String> out = new ArrayList<>();
        for (String token : splitCsvInput(terms == null ? "" : terms.replaceAll("\\s+", ","))) {
            String normalized = normalizeIdentifier(token);
            if (normalized.length() >= 2) out.add(normalized);
            String singular = singular(normalized);
            if (!singular.equals(normalized) && singular.length() >= 2) out.add(singular);
        }
        return out;
    }


    private boolean matchesAnyToken(String value, List<String> tokens) {
        for (String token : tokens) {
            if (containsNormalized(value, token)) return true;
        }
        return false;
    }

    private boolean containsNormalized(String value, String token) {
        if (value == null || token == null || token.isBlank()) return false;
        return normalizeIdentifier(value).contains(token);
    }

    private String qualify(String schema, String table) {
        if (schema == null || schema.isBlank()) return quoteIdent(table);
        return quoteIdent(schema) + "." + quoteIdent(table);
    }

    private String quoteIdent(String id) {
        if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
            throw new IllegalArgumentException("Illegal identifier: '" + id + "'");
        }
        if (dialect.kind() == DatabaseKind.ORACLE) return id;
        if (dialect.kind() == DatabaseKind.MSSQL) return "[" + id + "]";
        return "\"" + id + "\"";
    }
}
