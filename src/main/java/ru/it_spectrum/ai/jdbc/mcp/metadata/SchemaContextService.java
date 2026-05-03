package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Higher-level schema context assembled from the lower-level metadata and statistics services.
 *
 * <p>The tools backed by this service are intended for SQL-writing agents: compact schema
 * snapshots, table neighborhoods, and FK join paths. They deliberately cap traversal sizes so
 * large production schemas do not flood the model context by default.
 */
@Service
public class SchemaContextService {

    private static final int DEFAULT_MAX_TABLES = 50;
    private static final int MAX_TABLES_LIMIT = 300;
    private static final int DEFAULT_DEPTH = 1;
    private static final int MAX_DEPTH = 4;
    private static final int DEFAULT_MAX_PATHS = 5;
    private static final int MAX_PATHS_LIMIT = 25;

    private final MetadataService metadata;
    private final StatsService stats;

    public SchemaContextService(MetadataService metadata, StatsService stats) {
        this.metadata = metadata;
        this.stats = stats;
    }

    public Map<String, Object> schemaOverview(String schema, String namePattern,
                                              Boolean includeViews, Boolean includeStats,
                                              Boolean includeInferred, Integer maxTables) throws SQLException {
        int limit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        boolean views = includeViews == null || includeViews;
        boolean inferred = includeInferred == null || includeInferred;
        String types = views ? "TABLE,VIEW,MATERIALIZED VIEW" : "TABLE";

        List<Map<String, Object>> listed = metadata.listTables(schema, namePattern, parseTypes(types));
        boolean truncated = listed.size() > limit;
        List<Map<String, Object>> selected = listed.subList(0, Math.min(limit, listed.size()));

        List<Map<String, Object>> tables = new ArrayList<>(selected.size());
        List<Map<String, Object>> describedTables = new ArrayList<>(selected.size());
        List<Map<String, Object>> relationships = new ArrayList<>();
        Set<String> relationshipKeys = new HashSet<>();

        for (Map<String, Object> row : selected) {
            String tableSchema = str(row.get("schema"));
            String tableName = str(row.get("name"));
            if (tableName == null || tableName.isBlank()) continue;
            Map<String, Object> described;
            try {
                described = metadata.describeTable(tableSchema, tableName);
            } catch (SQLException e) {
                tables.add(errorTable(row, e));
                continue;
            }
            describedTables.add(described);
            tables.add(compactTable(described, Boolean.TRUE.equals(includeStats)));
            for (Map<String, Object> edge : outgoingEdges(described)) {
                addUnique(relationships, relationshipKeys, edge);
            }
        }
        if (inferred) {
            for (Map<String, Object> edge : inferRelationshipEdges(describedTables)) {
                addUnique(relationships, relationshipKeys, edge);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("namePattern", namePattern);
        out.put("includeViews", views);
        out.put("includeStats", Boolean.TRUE.equals(includeStats));
        out.put("includeInferred", inferred);
        out.put("tableCount", listed.size());
        out.put("returnedTableCount", tables.size());
        out.put("truncated", truncated);
        out.put("tables", tables);
        out.put("relationships", relationships);
        return out;
    }

    private Map<String, Object> errorTable(Map<String, Object> listedTable, SQLException error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", listedTable.get("schema"));
        out.put("name", listedTable.get("name"));
        out.put("type", listedTable.get("type"));
        out.put("remarks", listedTable.get("remarks"));
        out.put("error", error.getMessage());
        return out;
    }

    public Map<String, Object> tableContext(String schema, String table, Integer depth,
                                            Boolean includeIncoming, Boolean includeStats,
                                            Boolean includeInferred, Integer maxTables)
            throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must be provided");
        }
        int maxDepth = clamp(depth, DEFAULT_DEPTH, 0, MAX_DEPTH);
        boolean incoming = includeIncoming == null || includeIncoming;
        boolean inferred = includeInferred == null || includeInferred;

        Map<String, Object> root = metadata.describeTable(schema, table);
        String rootSchema = str(root.get("schema"));
        String rootTable = str(root.get("name"));

        Map<String, Map<String, Object>> schemaTables = inferred
                ? loadSchemaTables(rootSchema, clamp(maxTables, MAX_TABLES_LIMIT, 1, MAX_TABLES_LIMIT))
                : Map.of();
        List<Map<String, Object>> inferredEdges = inferred
                ? inferRelationshipEdges(new ArrayList<>(schemaTables.values()))
                : List.of();

        Map<String, Map<String, Object>> described = new LinkedHashMap<>();
        Queue<NodeDepth> queue = new ArrayDeque<>();
        described.put(key(rootSchema, rootTable), root);
        queue.add(new NodeDepth(rootSchema, rootTable, 0));

        while (!queue.isEmpty()) {
            NodeDepth current = queue.remove();
            if (current.depth >= maxDepth) continue;
            Map<String, Object> currentInfo = described.get(key(current.schema, current.table));
            if (currentInfo == null) continue;

            for (Neighbor neighbor : neighbors(currentInfo, incoming)) {
                String neighborKey = key(neighbor.schema, neighbor.table);
                if (described.containsKey(neighborKey)) continue;
                Map<String, Object> neighborInfo = metadata.describeTable(neighbor.schema, neighbor.table);
                described.put(neighborKey, neighborInfo);
                queue.add(new NodeDepth(neighbor.schema, neighbor.table, current.depth + 1));
            }
            if (inferred) {
                for (Neighbor neighbor : inferredNeighbors(currentInfo, inferredEdges, incoming)) {
                    String neighborKey = key(neighbor.schema, neighbor.table);
                    if (described.containsKey(neighborKey)) continue;
                    Map<String, Object> neighborInfo = schemaTables.get(neighborKey);
                    if (neighborInfo == null) continue;
                    described.put(neighborKey, neighborInfo);
                    queue.add(new NodeDepth(neighbor.schema, neighbor.table, current.depth + 1));
                }
            }
        }

        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> relationships = new ArrayList<>();
        Set<String> relationshipKeys = new HashSet<>();
        for (Map<String, Object> info : described.values()) {
            tables.add(compactTable(info, Boolean.TRUE.equals(includeStats)));
            for (Map<String, Object> edge : outgoingEdges(info)) {
                addUnique(relationships, relationshipKeys, edge);
            }
            if (incoming) {
                for (Map<String, Object> edge : incomingEdges(info)) {
                    addUnique(relationships, relationshipKeys, edge);
                }
            }
        }
        if (inferred) {
            Set<String> describedKeys = described.keySet();
            for (Map<String, Object> edge : inferredEdges) {
                if (describedKeys.contains(key(str(edge.get("fromSchema")), str(edge.get("fromTable"))))
                        && describedKeys.contains(key(str(edge.get("toSchema")), str(edge.get("toTable"))))) {
                    addUnique(relationships, relationshipKeys, edge);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rootSchema", rootSchema);
        out.put("rootTable", rootTable);
        out.put("depth", maxDepth);
        out.put("includeIncoming", incoming);
        out.put("includeStats", Boolean.TRUE.equals(includeStats));
        out.put("includeInferred", inferred);
        out.put("tables", tables);
        out.put("relationships", relationships);
        return out;
    }

    public Map<String, Object> findJoinPaths(String fromSchema, String fromTable,
                                             String toSchema, String toTable,
                                             Integer maxDepth, Integer maxPaths,
                                             Integer maxTables, Boolean includeInferred) throws SQLException {
        if (fromTable == null || fromTable.isBlank()) {
            throw new IllegalArgumentException("fromTable must be provided");
        }
        if (toTable == null || toTable.isBlank()) {
            throw new IllegalArgumentException("toTable must be provided");
        }
        int depthLimit = clamp(maxDepth, MAX_DEPTH, 1, MAX_DEPTH);
        int pathLimit = clamp(maxPaths, DEFAULT_MAX_PATHS, 1, MAX_PATHS_LIMIT);
        int tableLimit = clamp(maxTables, MAX_TABLES_LIMIT, 1, MAX_TABLES_LIMIT);
        boolean inferred = includeInferred == null || includeInferred;

        Map<String, Object> fromInfo = metadata.describeTable(fromSchema, fromTable);
        String effectiveFromSchema = str(fromInfo.get("schema"));
        String effectiveFromTable = str(fromInfo.get("name"));
        Map<String, Object> toInfo = metadata.describeTable(toSchema, toTable);
        String effectiveToSchema = str(toInfo.get("schema"));
        String effectiveToTable = str(toInfo.get("name"));

        Map<String, Map<String, Object>> described = loadSchemaTables(effectiveFromSchema, tableLimit);
        described.putIfAbsent(key(effectiveFromSchema, effectiveFromTable), fromInfo);
        described.putIfAbsent(key(effectiveToSchema, effectiveToTable), toInfo);

        List<GraphEdge> graphEdges = new ArrayList<>();
        for (Map<String, Object> info : described.values()) {
            for (Map<String, Object> edge : outgoingEdges(info)) {
                graphEdges.add(GraphEdge.forward(edge));
                graphEdges.add(GraphEdge.reverse(edge));
            }
        }
        if (inferred) {
            for (Map<String, Object> edge : inferRelationshipEdges(new ArrayList<>(described.values()))) {
                graphEdges.add(GraphEdge.forward(edge));
                graphEdges.add(GraphEdge.reverse(edge));
            }
        }

        Map<String, List<GraphEdge>> byFrom = new HashMap<>();
        for (GraphEdge edge : graphEdges) {
            byFrom.computeIfAbsent(key(edge.fromSchema, edge.fromTable), ignored -> new ArrayList<>()).add(edge);
        }

        String start = key(effectiveFromSchema, effectiveFromTable);
        String target = key(effectiveToSchema, effectiveToTable);
        List<List<Map<String, Object>>> paths = searchPaths(start, target, byFrom, depthLimit, pathLimit);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromSchema", effectiveFromSchema);
        out.put("fromTable", effectiveFromTable);
        out.put("toSchema", effectiveToSchema);
        out.put("toTable", effectiveToTable);
        out.put("maxDepth", depthLimit);
        out.put("includeInferred", inferred);
        out.put("schemaTablesScanned", described.size());
        out.put("pathCount", paths.size());
        out.put("paths", paths);
        return out;
    }

    private Map<String, Map<String, Object>> loadSchemaTables(String schema, int limit) throws SQLException {
        List<Map<String, Object>> listed = metadata.listTables(schema, "%", parseTypes("TABLE"));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        int count = 0;
        for (Map<String, Object> row : listed) {
            if (count >= limit) break;
            String tableSchema = str(row.get("schema"));
            String tableName = str(row.get("name"));
            if (tableName == null || tableName.isBlank()) continue;
            Map<String, Object> described = metadata.describeTable(tableSchema, tableName);
            out.put(key(str(described.get("schema")), str(described.get("name"))), described);
            count++;
        }
        return out;
    }

    private List<List<Map<String, Object>>> searchPaths(String start, String target,
                                                        Map<String, List<GraphEdge>> byFrom,
                                                        int maxDepth, int maxPaths) {
        List<List<Map<String, Object>>> paths = new ArrayList<>();
        Queue<PathState> queue = new ArrayDeque<>();
        queue.add(new PathState(start, List.of(), Set.of(start)));

        while (!queue.isEmpty() && paths.size() < maxPaths) {
            PathState state = queue.remove();
            if (state.edges.size() >= maxDepth) continue;
            for (GraphEdge edge : byFrom.getOrDefault(state.node, List.of())) {
                String next = key(edge.toSchema, edge.toTable);
                if (state.visited.contains(next)) continue;
                List<Map<String, Object>> nextEdges = new ArrayList<>(state.edges);
                nextEdges.add(edge.asMap());
                if (next.equals(target)) {
                    paths.add(nextEdges);
                    if (paths.size() >= maxPaths) break;
                } else {
                    Set<String> nextVisited = new HashSet<>(state.visited);
                    nextVisited.add(next);
                    queue.add(new PathState(next, nextEdges, nextVisited));
                }
            }
        }
        return paths;
    }

    private Map<String, Object> compactTable(Map<String, Object> info, boolean includeStats) throws SQLException {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        Set<String> pkColumns = new HashSet<>(stringList(mapValue(info.get("primaryKey")), "columns"));
        Set<String> fkColumns = new HashSet<>();
        for (Map<String, Object> fk : mapList(info.get("foreignKeys"))) {
            fkColumns.addAll(stringList(fk, "columns"));
        }
        Set<String> indexedColumns = new HashSet<>();
        for (Map<String, Object> index : mapList(info.get("indexes"))) {
            indexedColumns.addAll(stringList(index, "columns"));
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> col : mapList(info.get("columns"))) {
            String name = str(col.get("name"));
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("name", name);
            compact.put("type", col.get("typeName"));
            compact.put("nullable", col.get("nullable"));
            if (pkColumns.contains(name)) compact.put("primaryKey", true);
            if (fkColumns.contains(name)) compact.put("foreignKey", true);
            if (indexedColumns.contains(name)) compact.put("indexed", true);
            columns.add(compact);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("name", table);
        out.put("type", info.get("type"));
        out.put("remarks", info.get("remarks"));
        out.put("columns", columns);
        out.put("primaryKey", info.get("primaryKey"));
        out.put("constraints", info.get("constraints"));
        out.put("foreignKeys", info.get("foreignKeys"));
        out.put("indexes", compactIndexes(info.get("indexes")));
        out.put("triggers", info.get("triggers"));
        if (includeStats && table != null && !isView(info)) {
            try {
                out.put("stats", stats.tableStats(schema, table));
            } catch (SQLException e) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", e.getMessage());
                out.put("stats", error);
            }
        }
        return out;
    }

    private List<Map<String, Object>> compactIndexes(Object indexesValue) {
        List<Map<String, Object>> indexes = new ArrayList<>();
        for (Map<String, Object> index : mapList(indexesValue)) {
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("name", index.get("name"));
            compact.put("unique", index.get("unique"));
            compact.put("columns", index.get("columns"));
            indexes.add(compact);
        }
        return indexes;
    }

    private boolean isView(Map<String, Object> info) {
        String type = str(info.get("type"));
        return type != null && type.toUpperCase(Locale.ROOT).contains("VIEW");
    }

    private List<Neighbor> neighbors(Map<String, Object> info, boolean includeIncoming) {
        List<Neighbor> out = new ArrayList<>();
        for (Map<String, Object> fk : mapList(info.get("foreignKeys"))) {
            out.add(new Neighbor(str(fk.get("referencedSchema")), str(fk.get("referencedTable"))));
        }
        if (includeIncoming) {
            for (Map<String, Object> fk : mapList(info.get("referencedBy"))) {
                out.add(new Neighbor(str(fk.get("fromSchema")), str(fk.get("fromTable"))));
            }
        }
        out.removeIf(n -> n.table == null || n.table.isBlank());
        return out;
    }

    private List<Neighbor> inferredNeighbors(Map<String, Object> info,
                                             List<Map<String, Object>> inferredEdges,
                                             boolean includeIncoming) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        String currentKey = key(schema, table);
        List<Neighbor> out = new ArrayList<>();
        for (Map<String, Object> edge : inferredEdges) {
            String fromKey = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
            String toKey = key(str(edge.get("toSchema")), str(edge.get("toTable")));
            if (currentKey.equals(fromKey)) {
                out.add(new Neighbor(str(edge.get("toSchema")), str(edge.get("toTable"))));
            } else if (includeIncoming && currentKey.equals(toKey)) {
                out.add(new Neighbor(str(edge.get("fromSchema")), str(edge.get("fromTable"))));
            }
        }
        return out;
    }

    private List<Map<String, Object>> outgoingEdges(Map<String, Object> info) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> fk : mapList(info.get("foreignKeys"))) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("relationshipType", "foreignKey");
            edge.put("fkName", fk.get("name"));
            edge.put("fromSchema", schema);
            edge.put("fromTable", table);
            edge.put("fromColumns", fk.get("columns"));
            edge.put("toSchema", fk.get("referencedSchema"));
            edge.put("toTable", fk.get("referencedTable"));
            edge.put("toColumns", fk.get("referencedColumns"));
            out.add(edge);
        }
        return out;
    }

    private List<Map<String, Object>> incomingEdges(Map<String, Object> info) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> fk : mapList(info.get("referencedBy"))) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("relationshipType", "foreignKey");
            edge.put("fkName", fk.get("name"));
            edge.put("fromSchema", fk.get("fromSchema"));
            edge.put("fromTable", fk.get("fromTable"));
            edge.put("fromColumns", fk.get("fromColumns"));
            edge.put("toSchema", schema);
            edge.put("toTable", table);
            edge.put("toColumns", fk.get("toColumns"));
            out.add(edge);
        }
        return out;
    }

    private List<Map<String, Object>> inferRelationshipEdges(List<Map<String, Object>> tables) {
        Map<String, ReferencedColumn> referencedColumns = new HashMap<>();
        Set<String> realRelationshipKeys = new HashSet<>();
        for (Map<String, Object> table : tables) {
            for (Map<String, Object> edge : outgoingEdges(table)) {
                realRelationshipKeys.add(edgeKey(edge));
            }
            for (ReferencedColumn column : candidateReferencedColumns(table)) {
                referencedColumns.merge(column.matchName, column,
                        (existing, replacement) -> existing.primaryKey ? existing : replacement);
            }
        }

        List<Map<String, Object>> inferred = new ArrayList<>();
        Set<String> inferredKeys = new HashSet<>();
        for (Map<String, Object> table : tables) {
            String schema = str(table.get("schema"));
            String tableName = str(table.get("name"));
            for (Map<String, Object> column : mapList(table.get("columns"))) {
                String columnName = str(column.get("name"));
                if (columnName == null || isKnownKeyColumn(table, columnName)) continue;
                String referenceName = referenceNameFromColumn(columnName);
                if (referenceName == null) continue;
                ReferencedColumn referenced = referencedColumns.get(normalizeIdentifier(referenceName));
                if (referenced == null) continue;
                if (key(schema, tableName).equals(key(referenced.schema, referenced.table))) continue;
                if (!typesCompatible(str(column.get("typeName")), referenced.typeName)) continue;

                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("relationshipType", "inferred");
                edge.put("fkName", "inferred_" + tableName + "_" + columnName + "_to_" + referenced.table);
                edge.put("fromSchema", schema);
                edge.put("fromTable", tableName);
                edge.put("fromColumns", List.of(columnName));
                edge.put("toSchema", referenced.schema);
                edge.put("toTable", referenced.table);
                edge.put("toColumns", List.of(referenced.column));
                edge.put("confidence", confidence(columnName, referenced));
                edge.put("reason", "Column name matches target table name plus _id and data types are compatible");
                if (!realRelationshipKeys.contains(edgeKey(edge)) && inferredKeys.add(edgeKey(edge))) {
                    inferred.add(edge);
                }
            }
        }
        return inferred;
    }

    private List<ReferencedColumn> candidateReferencedColumns(Map<String, Object> table) {
        String schema = str(table.get("schema"));
        String tableName = str(table.get("name"));
        List<String> primaryKey = stringList(mapValue(table.get("primaryKey")), "columns");
        List<ReferencedColumn> out = new ArrayList<>();
        if (primaryKey.size() == 1) {
            Map<String, Object> column = columnByName(table, primaryKey.get(0));
            if (!column.isEmpty()) {
                out.add(new ReferencedColumn(schema, tableName, primaryKey.get(0),
                        str(column.get("typeName")), normalizeIdentifier(tableName), true));
                out.add(new ReferencedColumn(schema, tableName, primaryKey.get(0),
                        str(column.get("typeName")), singular(normalizeIdentifier(tableName)), true));
            }
        }
        for (Map<String, Object> unique : mapList(table.get("uniqueConstraints"))) {
            List<String> columns = stringList(unique, "columns");
            if (columns.size() != 1) continue;
            Map<String, Object> column = columnByName(table, columns.get(0));
            if (column.isEmpty()) continue;
            out.add(new ReferencedColumn(schema, tableName, columns.get(0),
                    str(column.get("typeName")), normalizeIdentifier(tableName), false));
            out.add(new ReferencedColumn(schema, tableName, columns.get(0),
                    str(column.get("typeName")), singular(normalizeIdentifier(tableName)), false));
        }
        return out;
    }

    private Map<String, Object> columnByName(Map<String, Object> table, String columnName) {
        for (Map<String, Object> column : mapList(table.get("columns"))) {
            if (columnName.equalsIgnoreCase(str(column.get("name")))) {
                return column;
            }
        }
        return Map.of();
    }

    private boolean isKnownKeyColumn(Map<String, Object> table, String columnName) {
        if (stringList(mapValue(table.get("primaryKey")), "columns").stream()
                .anyMatch(c -> c.equalsIgnoreCase(columnName))) {
            return true;
        }
        for (Map<String, Object> fk : mapList(table.get("foreignKeys"))) {
            if (stringList(fk, "columns").stream().anyMatch(c -> c.equalsIgnoreCase(columnName))) {
                return true;
            }
        }
        return false;
    }

    private String referenceNameFromColumn(String columnName) {
        String normalized = normalizeIdentifier(columnName);
        if (!normalized.endsWith("_id") || normalized.length() <= 3) return null;
        return normalized.substring(0, normalized.length() - 3);
    }

    private double confidence(String columnName, ReferencedColumn referenced) {
        String stem = referenceNameFromColumn(columnName);
        double score = 0.70;
        if (referenced.primaryKey) score += 0.15;
        if (stem != null && stem.equals(singular(normalizeIdentifier(referenced.table)))) score += 0.10;
        return Math.min(0.95, score);
    }

    private boolean typesCompatible(String leftType, String rightType) {
        if (leftType == null || rightType == null) return true;
        String left = typeFamily(leftType);
        String right = typeFamily(rightType);
        return left.equals(right);
    }

    private String typeFamily(String typeName) {
        String t = typeName.toLowerCase(Locale.ROOT);
        if (t.contains("int") || t.contains("number") || t.contains("numeric")
                || t.contains("decimal") || t.contains("serial")) {
            return "numeric";
        }
        if (t.contains("char") || t.contains("text") || t.contains("clob") || t.contains("uuid")) {
            return "text";
        }
        if (t.contains("date") || t.contains("time")) {
            return "temporal";
        }
        return t;
    }

    private String normalizeIdentifier(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "").replaceAll("_+$", "");
    }

    private String singular(String value) {
        if (value == null || value.length() < 2) return value;
        if (value.endsWith("ies") && value.length() > 3) {
            return value.substring(0, value.length() - 3) + "y";
        }
        if (value.endsWith("ses") && value.length() > 3) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("s") && !value.endsWith("ss")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void addUnique(List<Map<String, Object>> target, Set<String> seen, Map<String, Object> edge) {
        String k = edgeKey(edge);
        if (seen.add(k)) target.add(edge);
    }

    private String edgeKey(Map<String, Object> edge) {
        return key(str(edge.get("fromSchema")), str(edge.get("fromTable"))) + ":"
                + edge.get("fromColumns") + "->"
                + key(str(edge.get("toSchema")), str(edge.get("toTable"))) + ":"
                + edge.get("toColumns");
    }

    private String[] parseTypes(String types) {
        if (types == null || types.isBlank()) return null;
        String[] raw = types.split(",");
        List<String> cleaned = new ArrayList<>();
        for (String part : raw) {
            String type = part.trim();
            if (!type.isEmpty()) cleaned.add(type);
        }
        return cleaned.toArray(new String[0]);
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) return defaultValue;
        return Math.max(min, Math.min(max, value));
    }

    private String key(String schema, String table) {
        return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "."
                + (table == null ? "" : table.toLowerCase(Locale.ROOT));
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    private record Neighbor(String schema, String table) {
    }

    private record NodeDepth(String schema, String table, int depth) {
    }

    private record PathState(String node, List<Map<String, Object>> edges, Set<String> visited) {
    }

    private record ReferencedColumn(String schema, String table, String column, String typeName,
                                    String matchName, boolean primaryKey) {
    }

    private record GraphEdge(String direction, String relationshipType, String fkName,
                             String fromSchema, String fromTable, Object fromColumns,
                             String toSchema, String toTable, Object toColumns,
                             Object confidence, Object reason) {

        static GraphEdge forward(Map<String, Object> edge) {
            return new GraphEdge("forward",
                    strStatic(edge.get("relationshipType")),
                    strStatic(edge.get("fkName")),
                    strStatic(edge.get("fromSchema")),
                    strStatic(edge.get("fromTable")),
                    edge.get("fromColumns"),
                    strStatic(edge.get("toSchema")),
                    strStatic(edge.get("toTable")),
                    edge.get("toColumns"),
                    edge.get("confidence"),
                    edge.get("reason"));
        }

        static GraphEdge reverse(Map<String, Object> edge) {
            return new GraphEdge("reverse",
                    strStatic(edge.get("relationshipType")),
                    strStatic(edge.get("fkName")),
                    strStatic(edge.get("toSchema")),
                    strStatic(edge.get("toTable")),
                    edge.get("toColumns"),
                    strStatic(edge.get("fromSchema")),
                    strStatic(edge.get("fromTable")),
                    edge.get("fromColumns"),
                    edge.get("confidence"),
                    edge.get("reason"));
        }

        Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("direction", direction);
            out.put("relationshipType", relationshipType);
            out.put("fkName", fkName);
            out.put("fromSchema", fromSchema);
            out.put("fromTable", fromTable);
            out.put("fromColumns", fromColumns);
            out.put("toSchema", toSchema);
            out.put("toTable", toTable);
            out.put("toColumns", toColumns);
            out.put("joinCondition", joinCondition());
            if (confidence != null) out.put("confidence", confidence);
            if (reason != null) out.put("reason", reason);
            return out;
        }

        private String joinCondition() {
            List<String> left = objectList(fromColumns);
            List<String> right = objectList(toColumns);
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                parts.add(qualified(fromTable, left.get(i)) + " = " + qualified(toTable, right.get(i)));
            }
            return String.join(" AND ", parts);
        }

        private static String qualified(String table, String column) {
            return table + "." + column;
        }

        private static List<String> objectList(Object value) {
            if (!(value instanceof List<?> list)) return List.of();
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }

        private static String strStatic(Object value) {
            return Objects.toString(value, null);
        }
    }
}
