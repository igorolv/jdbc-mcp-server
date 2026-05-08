package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
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
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaQueryContextService extends SchemaContextSupport {

    SchemaQueryContextService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                              SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public Map<String, Object> queryContext(String schema, String terms, String tables,
                                            Boolean includeSamples, Integer maxTables) throws SQLException {
        int tableLimit = clamp(maxTables, 12, 1, 50);
        boolean samples = Boolean.TRUE.equals(includeSamples);
        List<String> requestedTables = splitCsvInput(tables);
        List<String> tokens = queryTokens(terms);

        Map<String, TableDescription> selected = new LinkedHashMap<>();
        Map<String, Map<String, Object>> semanticMatchesByTable = new LinkedHashMap<>();
        for (String tableName : requestedTables) {
            TableDescription described = metadata.describeTable(schema, tableName);
            selected.put(key(described.schema(), described.name()), described);
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
                semanticMatchesByTable.put(tableKey, candidate.toMap());
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

        List<Map<String, Object>> declaredEdges = new ArrayList<>();
        for (TableDescription info : selected.values()) declaredEdges.addAll(outgoingEdges(info));

        List<Map<String, Object>> tableContexts = new ArrayList<>();
        for (TableDescription info : selected.values()) {
            tableContexts.add(queryTableContext(info, tokens, samples,
                    semanticMatchesByTable.get(key(info.schema(), info.name()))));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("terms", terms);
        out.put("requestedTables", requestedTables);
        out.put("includeSamples", samples);
        out.put("tableCount", tableContexts.size());
        out.put("semanticMatches", semanticCandidates.stream()
                .map(SemanticTableCandidate::toMap)
                .toList());
        out.put("tables", tableContexts);
        out.put("relationships", graphEdges(declaredEdges));
        out.put("joinPaths", pairwiseJoinPaths(new ArrayList<>(selected.keySet()), declaredEdges));
        return out;
    }

    private Map<String, Object> queryTableContext(TableDescription info, List<String> tokens,
                                                  boolean includeSamples,
                                                  Map<String, Object> semanticMatch) {
        Map<String, Object> out = new LinkedHashMap<>();
        String schema = info.schema();
        String table = info.name();
        out.put("schema", schema);
        out.put("name", table);
        out.put("type", info.type());
        Map<String, TableDegree> degrees = new HashMap<>();
        degrees.put(key(schema, table), new TableDegree());
        out.put("classification", classifyTable(info, degrees));
        if (!isBlank(info.remarks())) out.put("remarks", info.remarks());
        out.put("primaryKey", info.primaryKey());
        out.put("allowedValues", info.allowedValues());
        out.put("relevantColumns", relevantColumns(info, tokens));
        out.put("constraints", compactCheckConstraints(info));
        out.put("foreignKeys", info.foreignKeys());
        out.put("indexes", compactIndexes(info.indexes()));
        if (semanticMatch != null) out.put("semanticMatch", semanticMatch);
        if (includeSamples) {
            Map<String, Object> sample = sampleRowsBestEffort(schema, table, 3);
            out.put("sample", sample);
        }
        return out;
    }

    private List<Map<String, Object>> relevantColumns(TableDescription info, List<String> tokens) {
        List<Map<String, Object>> columns = new ArrayList<>();
        Set<String> pk = new HashSet<>(stringList(mapValue(info.primaryKey()), "columns"));
        Set<String> fk = new HashSet<>();
        for (ForeignKey foreignKey : info.foreignKeys()) {
            fk.addAll(foreignKey.columns());
        }
        for (Column column : info.columns()) {
            String name = str(column.name());
            boolean tokenMatch = matchesAnyToken(name, tokens) || matchesAnyToken(str(column.remarks()), tokens);
            boolean important = pk.contains(name) || fk.contains(name) || tokenMatch
                    || mapValue(info.allowedValues()).containsKey(name);
            if (!important && !tokens.isEmpty()) continue;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("type", column.typeName());
            out.put("nullable", column.nullable());
            if (pk.contains(name)) out.put("primaryKey", true);
            if (fk.contains(name)) out.put("foreignKey", true);
            Object allowed = mapValue(info.allowedValues()).get(name);
            if (allowed != null) out.put("allowedValues", allowed);
            columns.add(out);
        }
        if (!columns.isEmpty() || tokens.isEmpty()) return columns;
        for (Column column : info.columns()) {
            if (columns.size() >= 8) break;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", column.name());
            out.put("type", column.typeName());
            out.put("nullable", column.nullable());
            columns.add(out);
        }
        return columns;
    }

    private List<Map<String, Object>> compactCheckConstraints(TableDescription info) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Constraint constraint : info.constraints()) {
            String type = str(constraint.type());
            if (!"CHECK".equalsIgnoreCase(type) && constraint.allowedValues() == null) continue;
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("name", constraint.name());
            compact.put("type", type);
            if (constraint.definition() != null) compact.put("definition", constraint.definition());
            if (constraint.allowedValuesColumn() != null) {
                compact.put("allowedValuesColumn", constraint.allowedValuesColumn());
                compact.put("allowedValues", constraint.allowedValues());
            }
            out.add(compact);
        }
        return out;
    }

    private Map<String, Object> sampleRowsBestEffort(String schema, String table, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String sql = dialect.limitQuery("SELECT * FROM " + qualify(schema, table), limit);
            QueryResult result = executor.queryInternal(sql, List.of(), limit);
            out.put("columns", result.columns());
            out.put("rows", result.rows());
            out.put("rowCount", result.rowCount());
        } catch (Exception e) {
            out.put("sampleError", e.getMessage());
        }
        return out;
    }

    private List<Map<String, Object>> pairwiseJoinPaths(List<String> tableKeys, List<Map<String, Object>> edges) {
        List<Map<String, Object>> paths = new ArrayList<>();
        for (int i = 0; i < tableKeys.size(); i++) {
            for (int j = i + 1; j < tableKeys.size(); j++) {
                Map<String, Object> path = shortestGraphPath(tableKeys.get(i), tableKeys.get(j), edges, MAX_DEPTH);
                if (Boolean.TRUE.equals(path.get("found"))) paths.add(path);
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
