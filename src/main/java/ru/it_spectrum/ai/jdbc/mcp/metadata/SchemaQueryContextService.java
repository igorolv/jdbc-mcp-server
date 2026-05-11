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
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Constraint;
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
            for (SemanticTableCandidate candidate : semanticCandidates) {
                if (selected.size() >= tableLimit) break;
                String candidateSchema = candidate.schema() == null ? schema : candidate.schema();
                TableDescription described;
                try {
                    described = metadata.describeTable(candidateSchema, candidate.table());
                } catch (SQLException ignored) {
                    continue;
                }
                String tableKey = key(described.schema(), described.name());
                selected.putIfAbsent(tableKey, described);
                semanticMatchesByTable.put(tableKey, candidate);
            }
        }

        if (selected.size() < tableLimit) {
            List<TableEntry> listed = metadata.listTables(schema, "%", parseTypes("TABLE,VIEW,MATERIALIZED VIEW"));
            List<TableScore> scored = new ArrayList<>();
            for (TableEntry row : listed) {
                String tableSchema = row.schema();
                String tableName = row.name();
                if (tableName == null || tableName.isBlank()) continue;
                if (selected.containsKey(key(tableSchema, tableName))) continue;
                TableDescription described;
                try {
                    described = metadata.describeTable(tableSchema, tableName);
                } catch (SQLException ignored) {
                    continue;
                }
                int score = relevanceScore(described, tokens);
                if (score > 0 || requestedTables.isEmpty() && tokens.isEmpty()) {
                    scored.add(new TableScore(described, score));
                }
            }
            scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
            for (TableScore score : scored) {
                if (selected.size() >= tableLimit) break;
                selected.put(key(str(score.table().schema()), str(score.table().name())), score.table());
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
        for (Constraint constraint : info.constraints()) {
            String type = str(constraint.type());
            if (!"CHECK".equalsIgnoreCase(type) && constraint.allowedValues() == null) continue;
            out.add(new QueryContextConstraint(
                    constraint.name(),
                    type,
                    constraint.definition(),
                    constraint.allowedValuesColumn(),
                    constraint.allowedValues()));
        }
        return out;
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
        return info.allowedValues() == null ? Map.of() : info.allowedValues();
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

    private int relevanceScore(TableDescription info, List<String> tokens) {
        if (tokens.isEmpty()) return 1;
        int score = 0;
        String tableName = info.name();
        String remarks = str(info.remarks());
        for (String token : tokens) {
            if (containsNormalized(tableName, token)) score += 10;
            if (containsNormalized(remarks, token)) score += 5;
            for (Column column : info.columns()) {
                if (containsNormalized(str(column.name()), token)) score += 4;
                if (containsNormalized(str(column.remarks()), token)) score += 2;
            }
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
