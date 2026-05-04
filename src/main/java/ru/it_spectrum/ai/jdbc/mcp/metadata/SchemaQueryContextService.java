package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
class SchemaQueryContextService extends SchemaContextSupport {

    SchemaQueryContextService(MetadataService metadata, StatsService stats, SqlExecutor executor, SqlDialect dialect) {
        super(metadata, stats, executor, dialect);
    }

    public Map<String, Object> queryContext(String schema, String terms, String tables,
                                            Boolean includeSamples, Integer maxTables,
                                            Boolean includeInferred) throws SQLException {
        int tableLimit = clamp(maxTables, 12, 1, 50);
        boolean samples = Boolean.TRUE.equals(includeSamples);
        boolean inferred = includeInferred == null || includeInferred;
        List<String> requestedTables = splitCsvInput(tables);
        List<String> tokens = queryTokens(terms);

        Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
        for (String tableName : requestedTables) {
            Map<String, Object> described = metadata.describeTable(schema, tableName);
            selected.put(key(str(described.get("schema")), str(described.get("name"))), described);
        }

        if (selected.size() < tableLimit) {
            List<Map<String, Object>> listed = metadata.listTables(schema, "%", parseTypes("TABLE,VIEW,MATERIALIZED VIEW"));
            List<TableScore> scored = new ArrayList<>();
            for (Map<String, Object> row : listed) {
                String tableSchema = str(row.get("schema"));
                String tableName = str(row.get("name"));
                if (tableName == null || tableName.isBlank()) continue;
                if (selected.containsKey(key(tableSchema, tableName))) continue;
                Map<String, Object> described;
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
                selected.put(key(str(score.table().get("schema")), str(score.table().get("name"))), score.table());
            }
        }

        List<Map<String, Object>> declaredEdges = new ArrayList<>();
        for (Map<String, Object> info : selected.values()) declaredEdges.addAll(outgoingEdges(info));
        List<Map<String, Object>> inferredEdges = inferred
                ? inferRelationshipEdges(new ArrayList<>(selected.values()))
                : List.of();
        List<Map<String, Object>> allEdges = new ArrayList<>(declaredEdges);
        allEdges.addAll(inferredEdges);

        List<Map<String, Object>> tableContexts = new ArrayList<>();
        for (Map<String, Object> info : selected.values()) {
            tableContexts.add(queryTableContext(info, tokens, samples));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("terms", terms);
        out.put("requestedTables", requestedTables);
        out.put("includeSamples", samples);
        out.put("includeInferred", inferred);
        out.put("tableCount", tableContexts.size());
        out.put("tables", tableContexts);
        out.put("relationships", graphEdges(allEdges));
        out.put("joinPaths", pairwiseJoinPaths(new ArrayList<>(selected.keySet()), allEdges));
        return out;
    }

    private Map<String, Object> queryTableContext(Map<String, Object> info, List<String> tokens,
                                                  boolean includeSamples) {
        Map<String, Object> out = new LinkedHashMap<>();
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        out.put("schema", schema);
        out.put("name", table);
        out.put("type", info.get("type"));
        Map<String, TableDegree> degrees = new HashMap<>();
        degrees.put(key(schema, table), new TableDegree());
        out.put("classification", classifyTable(info, degrees));
        if (!isBlank(info.get("remarks"))) out.put("remarks", info.get("remarks"));
        out.put("primaryKey", info.get("primaryKey"));
        out.put("allowedValues", info.get("allowedValues"));
        out.put("relevantColumns", relevantColumns(info, tokens));
        out.put("constraints", compactCheckConstraints(info));
        out.put("foreignKeys", info.get("foreignKeys"));
        out.put("indexes", compactIndexes(info.get("indexes")));
        if (includeSamples) {
            Map<String, Object> sample = sampleRowsBestEffort(schema, table, 3);
            out.put("sample", sample);
        }
        return out;
    }

    private List<Map<String, Object>> relevantColumns(Map<String, Object> info, List<String> tokens) {
        List<Map<String, Object>> columns = new ArrayList<>();
        Set<String> pk = new HashSet<>(stringList(mapValue(info.get("primaryKey")), "columns"));
        Set<String> fk = new HashSet<>();
        for (Map<String, Object> foreignKey : mapList(info.get("foreignKeys"))) {
            fk.addAll(stringList(foreignKey, "columns"));
        }
        for (Map<String, Object> column : mapList(info.get("columns"))) {
            String name = str(column.get("name"));
            boolean tokenMatch = matchesAnyToken(name, tokens) || matchesAnyToken(str(column.get("remarks")), tokens);
            boolean important = pk.contains(name) || fk.contains(name) || tokenMatch
                    || mapValue(info.get("allowedValues")).containsKey(name);
            if (!important && !tokens.isEmpty()) continue;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("type", column.get("typeName"));
            out.put("nullable", column.get("nullable"));
            if (pk.contains(name)) out.put("primaryKey", true);
            if (fk.contains(name)) out.put("foreignKey", true);
            Object allowed = mapValue(info.get("allowedValues")).get(name);
            if (allowed != null) out.put("allowedValues", allowed);
            columns.add(out);
        }
        if (!columns.isEmpty() || tokens.isEmpty()) return columns;
        for (Map<String, Object> column : mapList(info.get("columns"))) {
            if (columns.size() >= 8) break;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", column.get("name"));
            out.put("type", column.get("typeName"));
            out.put("nullable", column.get("nullable"));
            columns.add(out);
        }
        return columns;
    }

    private List<Map<String, Object>> compactCheckConstraints(Map<String, Object> info) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> constraint : mapList(info.get("constraints"))) {
            String type = str(constraint.get("type"));
            if (!"CHECK".equalsIgnoreCase(type) && constraint.get("allowedValues") == null) continue;
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("name", constraint.get("name"));
            compact.put("type", type);
            if (constraint.get("definition") != null) compact.put("definition", constraint.get("definition"));
            if (constraint.get("allowedValuesColumn") != null) {
                compact.put("allowedValuesColumn", constraint.get("allowedValuesColumn"));
                compact.put("allowedValues", constraint.get("allowedValues"));
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

    private int relevanceScore(Map<String, Object> info, List<String> tokens) {
        if (tokens.isEmpty()) return 1;
        int score = 0;
        String tableName = str(info.get("name"));
        String remarks = str(info.get("remarks"));
        for (String token : tokens) {
            if (containsNormalized(tableName, token)) score += 10;
            if (containsNormalized(remarks, token)) score += 5;
            for (Map<String, Object> column : mapList(info.get("columns"))) {
                if (containsNormalized(str(column.get("name")), token)) score += 4;
                if (containsNormalized(str(column.get("remarks")), token)) score += 2;
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
