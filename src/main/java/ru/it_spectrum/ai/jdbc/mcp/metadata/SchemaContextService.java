package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

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
    private static final int DEFAULT_MAX_FINDINGS = 200;
    private static final int MAX_FINDINGS_LIMIT = 1_000;

    private final MetadataService metadata;
    private final StatsService stats;
    private final SqlExecutor executor;
    private final SqlDialect dialect;

    public SchemaContextService(MetadataService metadata, StatsService stats,
                                SqlExecutor executor, SqlDialect dialect) {
        this.metadata = metadata;
        this.stats = stats;
        this.executor = executor;
        this.dialect = dialect;
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
                                            Boolean includeInferred, Integer inferredScanLimit)
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
                ? loadSchemaTables(rootSchema, clamp(inferredScanLimit, MAX_TABLES_LIMIT, 1, MAX_TABLES_LIMIT))
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
                                             Integer scanLimit, Boolean includeInferred) throws SQLException {
        if (fromTable == null || fromTable.isBlank()) {
            throw new IllegalArgumentException("fromTable must be provided");
        }
        if (toTable == null || toTable.isBlank()) {
            throw new IllegalArgumentException("toTable must be provided");
        }
        int depthLimit = clamp(maxDepth, MAX_DEPTH, 1, MAX_DEPTH);
        int pathLimit = clamp(maxPaths, DEFAULT_MAX_PATHS, 1, MAX_PATHS_LIMIT);
        int tableLimit = clamp(scanLimit, MAX_TABLES_LIMIT, 1, MAX_TABLES_LIMIT);
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

    public Map<String, Object> schemaLint(String schema, String table, String checks,
                                          Integer maxTables, Integer maxFindings,
                                          Boolean includeInferred) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        int findingLimit = clamp(maxFindings, DEFAULT_MAX_FINDINGS, 1, MAX_FINDINGS_LIMIT);
        boolean inferred = includeInferred == null || includeInferred;
        Set<String> enabledChecks = parseChecks(checks);

        Map<String, Map<String, Object>> tables = table != null && !table.isBlank()
                ? loadSingleTable(schema, table)
                : loadSchemaTables(schema, tableLimit);
        List<Map<String, Object>> inferredEdges = inferred
                ? inferRelationshipEdges(new ArrayList<>(tables.values()))
                : List.of();

        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> info : tables.values()) {
            lintTable(info, enabledChecks, findings, findingLimit);
            if (findings.size() >= findingLimit) break;
        }
        if (findings.size() < findingLimit) {
            lintRelationships(tables, inferredEdges, enabledChecks, findings, findingLimit);
        }
        if (findings.size() < findingLimit) {
            lintGraph(tables, enabledChecks, findings, findingLimit);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("table", table);
        out.put("checks", enabledChecks);
        out.put("includeInferred", inferred);
        out.put("tablesScanned", tables.size());
        out.put("findingCount", findings.size());
        out.put("truncated", findings.size() >= findingLimit);
        out.put("findings", findings);
        return out;
    }

    public String schemaBrief(String schema, String focus, Integer maxTables,
                              Boolean includeInferred) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        boolean inferred = includeInferred == null || includeInferred;
        Map<String, Map<String, Object>> tables = loadBriefTables(schema, focus, tableLimit);
        List<Map<String, Object>> fkEdges = new ArrayList<>();
        for (Map<String, Object> info : tables.values()) {
            fkEdges.addAll(outgoingEdges(info));
        }
        List<Map<String, Object>> inferredEdges = inferred
                ? inferRelationshipEdges(new ArrayList<>(tables.values()))
                : List.of();

        Map<String, TableDegree> degrees = tableDegrees(tables, fkEdges, inferredEdges);
        List<Map<String, Object>> tableList = new ArrayList<>(tables.values());
        tableList.sort((a, b) -> Integer.compare(
                degrees.getOrDefault(key(str(b.get("schema")), str(b.get("name"))), TableDegree.ZERO).total(),
                degrees.getOrDefault(key(str(a.get("schema")), str(a.get("name"))), TableDegree.ZERO).total()));

        StringBuilder sb = new StringBuilder();
        sb.append("Schema brief");
        if (schema != null && !schema.isBlank()) sb.append(" for ").append(schema);
        if (focus != null && !focus.isBlank()) sb.append(" (focus: ").append(focus).append(')');
        sb.append('\n');
        sb.append("- Tables scanned: ").append(tables.size()).append('\n');
        sb.append("- Declared relationships: ").append(fkEdges.size()).append('\n');
        if (inferred) sb.append("- Inferred relationships: ").append(inferredEdges.size()).append('\n');

        appendTableSection(sb, "Hub tables", tableList, degrees, "hub", 8);
        appendTableSection(sb, "Fact/detail tables", tableList, degrees, "fact/detail", 10);
        appendTableSection(sb, "Lookup/reference tables", tableList, degrees, "lookup/reference", 10);
        appendRelationshipSection(sb, "Key relationships", fkEdges, 12);
        if (inferred) appendRelationshipSection(sb, "Suspicious implicit joins", inferredEdges, 10);
        appendEnumSection(sb, tableList, 12);
        appendTableSummarySection(sb, tableList, degrees, 15);
        return sb.toString().trim();
    }

    public Map<String, Object> schemaGraph(String schema, Integer maxTables,
                                           Boolean includeInferred,
                                           String fromTable, String toTable,
                                           Integer maxDepth) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        boolean inferred = includeInferred == null || includeInferred;
        int depthLimit = clamp(maxDepth, MAX_DEPTH, 1, MAX_DEPTH);

        Map<String, Map<String, Object>> tables = loadSchemaTables(schema, tableLimit);
        List<Map<String, Object>> declaredEdges = new ArrayList<>();
        for (Map<String, Object> info : tables.values()) declaredEdges.addAll(outgoingEdges(info));
        List<Map<String, Object>> inferredEdges = inferred
                ? inferRelationshipEdges(new ArrayList<>(tables.values()))
                : List.of();
        List<Map<String, Object>> allEdges = new ArrayList<>(declaredEdges);
        allEdges.addAll(inferredEdges);

        Map<String, TableDegree> degrees = tableDegrees(tables, declaredEdges, inferredEdges);
        Map<String, List<String>> adjacency = undirectedAdjacency(tables, allEdges);
        List<Map<String, Object>> nodes = graphNodes(tables, degrees);
        List<Map<String, Object>> components = connectedComponents(tables, adjacency);
        List<Map<String, Object>> cycles = cycleHints(tables, allEdges, 25);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", schema);
        out.put("includeInferred", inferred);
        out.put("tablesScanned", tables.size());
        out.put("nodeCount", nodes.size());
        out.put("edgeCount", allEdges.size());
        out.put("declaredEdgeCount", declaredEdges.size());
        out.put("inferredEdgeCount", inferredEdges.size());
        out.put("centralTables", centralTables(nodes, 10));
        out.put("isolatedTables", isolatedTables(nodes));
        out.put("connectedComponents", components);
        out.put("cycles", cycles);
        out.put("nodes", nodes);
        out.put("edges", graphEdges(allEdges));

        if (fromTable != null && !fromTable.isBlank() && toTable != null && !toTable.isBlank()) {
            String fromKey = resolveTableKey(tables, schema, fromTable);
            String toKey = resolveTableKey(tables, schema, toTable);
            out.put("shortestPath", shortestGraphPath(fromKey, toKey, allEdges, depthLimit));
        }
        return out;
    }

    public String schemaGraphDot(String schema, String tables, Boolean includeInferred) throws SQLException {
        int tableLimit = clamp(null, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        boolean inferred = includeInferred == null || includeInferred;
        List<String> filterTables = splitCsvInput(tables);

        Map<String, Map<String, Object>> allTables = loadSchemaTables(schema, tableLimit);
        Map<String, Map<String, Object>> selected;
        if (!filterTables.isEmpty()) {
            selected = new LinkedHashMap<>();
            for (String t : filterTables) {
                String key = resolveTableKey(allTables, schema, t);
                if (key != null && allTables.containsKey(key)) {
                    selected.put(key, allTables.get(key));
                }
            }
        } else {
            selected = allTables;
        }

        List<Map<String, Object>> declaredEdges = new ArrayList<>();
        for (Map<String, Object> info : selected.values()) {
            declaredEdges.addAll(outgoingEdges(info));
        }
        declaredEdges.removeIf(e -> !selected.containsKey(key(str(e.get("fromSchema")), str(e.get("fromTable"))))
                || !selected.containsKey(key(str(e.get("toSchema")), str(e.get("toTable")))));

        List<Map<String, Object>> inferredEdges = inferred
                ? inferRelationshipEdges(new ArrayList<>(selected.values()))
                : new ArrayList<>();
        inferredEdges.removeIf(e -> !selected.containsKey(key(str(e.get("fromSchema")), str(e.get("fromTable"))))
                || !selected.containsKey(key(str(e.get("toSchema")), str(e.get("toTable")))));

        Map<String, Set<String>> pkCols = new HashMap<>();
        Map<String, Set<String>> fkCols = new HashMap<>();
        for (Map<String, Object> edge : declaredEdges) {
            String fromKey = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
            for (String col : stringList(edge, "fromColumns")) {
                fkCols.computeIfAbsent(fromKey, k -> new HashSet<>()).add(col);
            }
        }
        for (Map<String, Object> edge : inferredEdges) {
            String fromKey = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
            for (String col : stringList(edge, "fromColumns")) {
                fkCols.computeIfAbsent(fromKey, k -> new HashSet<>()).add(col);
            }
        }
        for (Map<String, Object> info : selected.values()) {
            String tableKey = key(str(info.get("schema")), str(info.get("name")));
            for (String pkCol : stringList(mapValue(info.get("primaryKey")), "columns")) {
                pkCols.computeIfAbsent(tableKey, k -> new HashSet<>()).add(pkCol);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("digraph ").append(dotId("schema_erd")).append(" {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  label=").append(dotString("Schema: " + (schema != null ? schema : "default"))).append(";\n");
        sb.append("  node [shape=record, fontname=\"Helvetica\", fontsize=10];\n");
        sb.append("  edge [fontname=\"Helvetica\", fontsize=9];\n");
        sb.append('\n');

        for (Map<String, Object> info : selected.values()) {
            String tableSchema = str(info.get("schema"));
            String tableName = str(info.get("name"));
            String tableKey = key(tableSchema, tableName);
            Set<String> pk = pkCols.getOrDefault(tableKey, Set.of());
            Set<String> fk = fkCols.getOrDefault(tableKey, Set.of());

            sb.append("  ").append(dotId(tableKey)).append(" [label=<{<b>")
                    .append(escapeHtml(tableName)).append("</b>|");

            List<Map<String, Object>> columns = mapList(info.get("columns"));
            for (int i = 0; i < columns.size(); i++) {
                Map<String, Object> col = columns.get(i);
                String colName = str(col.get("name"));
                String typeName = str(col.get("typeName"));
                if (i > 0) sb.append("<br align=\"left\"/>");
                if (pk.contains(colName)) sb.append("&#128273; ");
                else if (fk.contains(colName)) sb.append("&#8594; ");
                else sb.append("  ");
                sb.append("<i>").append(escapeHtml(colName)).append("</i>: ").append(escapeHtml(typeName));
            }
            sb.append("}>];\n");
        }

        sb.append('\n');

        for (Map<String, Object> edge : declaredEdges) {
            sb.append("  ").append(dotId(key(str(edge.get("fromSchema")), str(edge.get("fromTable")))))
                    .append(" -> ").append(dotId(key(str(edge.get("toSchema")), str(edge.get("toTable")))))
                    .append(" [label=").append(dotString(joinCondition(edge)))
                    .append(", style=solid];\n");
        }
        for (Map<String, Object> edge : inferredEdges) {
            sb.append("  ").append(dotId(key(str(edge.get("fromSchema")), str(edge.get("fromTable")))))
                    .append(" -> ").append(dotId(key(str(edge.get("toSchema")), str(edge.get("toTable")))))
                    .append(" [label=").append(dotString(joinCondition(edge)))
                    .append(", style=dashed, color=gray];\n");
        }

        sb.append("}\n");
        return sb.toString();
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
            scored.sort((a, b) -> Integer.compare(b.score, a.score));
            for (TableScore score : scored) {
                if (selected.size() >= tableLimit) break;
                selected.put(key(str(score.table.get("schema")), str(score.table.get("name"))), score.table);
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

    private Map<String, Map<String, Object>> loadBriefTables(String schema, String focus, int limit)
            throws SQLException {
        String pattern = focus == null || focus.isBlank() ? "%" : "%" + focus + "%";
        List<Map<String, Object>> listed = metadata.listTables(schema, pattern, parseTypes("TABLE"));
        if (listed.isEmpty() && focus != null && !focus.isBlank()) {
            listed = metadata.listTables(schema, "%", parseTypes("TABLE"));
        }
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

    private Map<String, Map<String, Object>> loadSingleTable(String schema, String table) throws SQLException {
        Map<String, Object> described = metadata.describeTable(schema, table);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(key(str(described.get("schema")), str(described.get("name"))), described);
        return out;
    }

    private void lintTable(Map<String, Object> info, Set<String> checks,
                           List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        List<Map<String, Object>> columns = mapList(info.get("columns"));
        boolean isTable = !isView(info);

        if (checkEnabled(checks, "missingPrimaryKey") && isTable && mapValue(info.get("primaryKey")).isEmpty()) {
            addFinding(findings, limit, "HIGH", "missingPrimaryKey", schema, table, null,
                    "Table has no primary key", "Add a primary key if rows need stable identity or joins.");
        }

        if (checkEnabled(checks, "missingRemarks") && isBlank(info.get("remarks"))) {
            addFinding(findings, limit, "LOW", "missingTableRemarks", schema, table, null,
                    "Object has no table remarks/comment", "Add a table comment to improve schema discoverability.");
        }

        if (checkEnabled(checks, "wideTable") && columns.size() > 50) {
            addFinding(findings, limit, "LOW", "wideTable", schema, table, null,
                    "Table has " + columns.size() + " columns", "Review whether the table mixes multiple concerns.");
        }

        if (checkEnabled(checks, "nullableUnique")) {
            lintNullableUniqueColumns(info, findings, limit);
        }
        if (checkEnabled(checks, "unconstrainedStatus")) {
            lintUnconstrainedStatusColumns(info, findings, limit);
        }
        if (checkEnabled(checks, "orphanIdColumn")) {
            lintOrphanIdColumns(info, findings, limit);
        }
    }

    private void lintNullableUniqueColumns(Map<String, Object> info,
                                           List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        for (Map<String, Object> unique : mapList(info.get("uniqueConstraints"))) {
            for (String columnName : stringList(unique, "columns")) {
                Map<String, Object> column = columnByName(info, columnName);
                if (Boolean.TRUE.equals(column.get("nullable"))) {
                    addFinding(findings, limit, "MEDIUM", "nullableUniqueColumn", schema, table, columnName,
                            "Unique constraint includes a nullable column",
                            "Check database NULL semantics before relying on this for uniqueness.");
                }
            }
        }
    }

    private void lintUnconstrainedStatusColumns(Map<String, Object> info,
                                                List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        for (Map<String, Object> column : mapList(info.get("columns"))) {
            String columnName = str(column.get("name"));
            String normalized = normalizeIdentifier(columnName);
            if (!("status".equals(normalized) || "type".equals(normalized) || normalized.endsWith("_status")
                    || normalized.endsWith("_type"))) {
                continue;
            }
            if (hasCheckConstraintForColumn(info, columnName)) continue;
            addFinding(findings, limit, "LOW", "unconstrainedStatusColumn", schema, table, columnName,
                    "Status/type-like column has no CHECK constraint",
                    "Consider a CHECK constraint or lookup table so agents know valid values.");
        }
    }

    private void lintOrphanIdColumns(Map<String, Object> info,
                                     List<Map<String, Object>> findings, int limit) {
        String schema = str(info.get("schema"));
        String table = str(info.get("name"));
        for (Map<String, Object> column : mapList(info.get("columns"))) {
            String columnName = str(column.get("name"));
            if (referenceNameFromColumn(columnName) == null || isKnownKeyColumn(info, columnName)) continue;
            addFinding(findings, limit, "LOW", "orphanIdColumn", schema, table, columnName,
                    "Column looks like a foreign key but no declared FK exists",
                    "Declare the FK or rely on schemaLint inferredRelationship findings if this is intentional.");
        }
    }

    private void lintRelationships(Map<String, Map<String, Object>> tables,
                                   List<Map<String, Object>> inferredEdges,
                                   Set<String> checks,
                                   List<Map<String, Object>> findings, int limit) {
        if (checkEnabled(checks, "fkWithoutIndex")) {
            for (Map<String, Object> info : tables.values()) {
                String schema = str(info.get("schema"));
                String table = str(info.get("name"));
                List<List<String>> indexColumns = indexColumns(info);
                for (Map<String, Object> fk : mapList(info.get("foreignKeys"))) {
                    List<String> fkColumns = stringList(fk, "columns");
                    if (isCoveredByIndex(fkColumns, indexColumns)) continue;
                    addFinding(findings, limit, "MEDIUM", "fkWithoutIndex", schema, table,
                            String.join(",", fkColumns),
                            "Foreign key has no supporting index on the child side",
                            "Create an index starting with the FK columns to improve joins and parent deletes/updates.");
                }
            }
        }

        if (checkEnabled(checks, "inferredRelationship")) {
            for (Map<String, Object> edge : inferredEdges) {
                addFinding(findings, limit, "MEDIUM", "inferredRelationship",
                        str(edge.get("fromSchema")), str(edge.get("fromTable")),
                        String.join(",", objectList(edge.get("fromColumns"))),
                        "Column naming suggests an undeclared relationship to "
                                + edge.get("toTable") + "." + edge.get("toColumns"),
                        "Declare a foreign key if this relationship is real.");
            }
        }

        if (checkEnabled(checks, "fkTypeMismatch")) {
            lintFkTypeMismatch(tables, findings, limit);
        }
    }

    private void lintFkTypeMismatch(Map<String, Map<String, Object>> tables,
                                    List<Map<String, Object>> findings, int limit) {
        for (Map<String, Object> info : tables.values()) {
            String schema = str(info.get("schema"));
            String table = str(info.get("name"));
            for (Map<String, Object> fk : mapList(info.get("foreignKeys"))) {
                String targetKey = key(str(fk.get("referencedSchema")), str(fk.get("referencedTable")));
                Map<String, Object> target = tables.get(targetKey);
                if (target == null) continue;
                List<String> left = stringList(fk, "columns");
                List<String> right = stringList(fk, "referencedColumns");
                for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                    Map<String, Object> leftColumn = columnByName(info, left.get(i));
                    Map<String, Object> rightColumn = columnByName(target, right.get(i));
                    String leftType = str(leftColumn.get("typeName"));
                    String rightType = str(rightColumn.get("typeName"));
                    if (typesCompatible(leftType, rightType)) continue;
                    addFinding(findings, limit, "HIGH", "fkTypeMismatch", schema, table, left.get(i),
                            "Foreign key column type " + leftType + " differs from referenced type " + rightType,
                            "Align FK and referenced column types to avoid casts and planner mistakes.");
                }
            }
        }
    }

    private void lintGraph(Map<String, Map<String, Object>> tables, Set<String> checks,
                           List<Map<String, Object>> findings, int limit) {
        if (!checkEnabled(checks, "isolatedTable")) return;
        Map<String, Integer> degrees = new HashMap<>();
        for (String tableKey : tables.keySet()) degrees.put(tableKey, 0);
        for (Map<String, Object> info : tables.values()) {
            String from = key(str(info.get("schema")), str(info.get("name")));
            for (Map<String, Object> edge : outgoingEdges(info)) {
                String to = key(str(edge.get("toSchema")), str(edge.get("toTable")));
                degrees.computeIfPresent(from, (k, v) -> v + 1);
                degrees.computeIfPresent(to, (k, v) -> v + 1);
            }
        }
        for (Map.Entry<String, Integer> entry : degrees.entrySet()) {
            if (entry.getValue() != 0) continue;
            Map<String, Object> info = tables.get(entry.getKey());
            addFinding(findings, limit, "LOW", "isolatedTable",
                    str(info.get("schema")), str(info.get("name")), null,
                    "Table has no declared FK relationships in the scanned set",
                    "Verify whether joins rely on naming conventions or application logic.");
        }
    }

    private Map<String, TableDegree> tableDegrees(Map<String, Map<String, Object>> tables,
                                                  List<Map<String, Object>> fkEdges,
                                                  List<Map<String, Object>> inferredEdges) {
        Map<String, TableDegree> degrees = new HashMap<>();
        for (String tableKey : tables.keySet()) degrees.put(tableKey, new TableDegree());
        for (Map<String, Object> edge : fkEdges) incrementDegrees(degrees, edge);
        for (Map<String, Object> edge : inferredEdges) incrementDegrees(degrees, edge);
        return degrees;
    }

    private void incrementDegrees(Map<String, TableDegree> degrees, Map<String, Object> edge) {
        String from = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
        String to = key(str(edge.get("toSchema")), str(edge.get("toTable")));
        TableDegree fromDegree = degrees.get(from);
        if (fromDegree != null) fromDegree.out++;
        TableDegree toDegree = degrees.get(to);
        if (toDegree != null) toDegree.in++;
    }

    private void appendTableSection(StringBuilder sb, String title, List<Map<String, Object>> tables,
                                    Map<String, TableDegree> degrees, String classification, int limit) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            if (!classification.equals(classifyTable(table, degrees))) continue;
            lines.add("- " + qualifiedName(table) + " (" + columnCount(table) + " cols, "
                    + relationshipSummary(table, degrees) + ")");
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) return;
        sb.append('\n').append(title).append('\n');
        for (String line : lines) sb.append(line).append('\n');
    }

    private void appendRelationshipSection(StringBuilder sb, String title,
                                           List<Map<String, Object>> edges, int limit) {
        if (edges.isEmpty()) return;
        sb.append('\n').append(title).append('\n');
        int count = 0;
        for (Map<String, Object> edge : edges) {
            if (count++ >= limit) {
                sb.append("- ... ").append(edges.size() - limit).append(" more\n");
                break;
            }
            sb.append("- ")
                    .append(edge.get("fromTable")).append('.').append(String.join("+", objectList(edge.get("fromColumns"))))
                    .append(" -> ")
                    .append(edge.get("toTable")).append('.').append(String.join("+", objectList(edge.get("toColumns"))));
            if ("inferred".equals(edge.get("relationshipType"))) {
                sb.append(" (inferred");
                if (edge.get("confidence") != null) sb.append(", confidence ").append(edge.get("confidence"));
                sb.append(')');
            }
            sb.append('\n');
        }
    }

    private void appendEnumSection(StringBuilder sb, List<Map<String, Object>> tables, int limit) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> table : tables) {
            for (Map<String, Object> constraint : mapList(table.get("constraints"))) {
                if (!"CHECK".equalsIgnoreCase(str(constraint.get("type")))) continue;
                EnumLikeConstraint enumLike = enumLikeConstraint(table, constraint);
                if (enumLike == null) continue;
                lines.add("- " + qualifiedName(table) + "." + enumLike.column + " in "
                        + enumLike.values);
                if (lines.size() >= limit) break;
            }
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) return;
        sb.append('\n').append("Enum-like columns").append('\n');
        for (String line : lines) sb.append(line).append('\n');
    }

    private void appendTableSummarySection(StringBuilder sb, List<Map<String, Object>> tables,
                                           Map<String, TableDegree> degrees, int limit) {
        if (tables.isEmpty()) return;
        sb.append('\n').append("Table notes").append('\n');
        int count = 0;
        for (Map<String, Object> table : tables) {
            if (count++ >= limit) {
                sb.append("- ... ").append(tables.size() - limit).append(" more\n");
                break;
            }
            sb.append("- ").append(qualifiedName(table))
                    .append(": ").append(classifyTable(table, degrees))
                    .append(", PK ").append(primaryKeySummary(table))
                    .append(", columns ").append(topColumns(table, 8));
            if (!isBlank(table.get("remarks"))) {
                sb.append(", remarks: ").append(shorten(str(table.get("remarks")), 80));
            }
            sb.append('\n');
        }
    }

    private List<Map<String, Object>> graphNodes(Map<String, Map<String, Object>> tables,
                                                 Map<String, TableDegree> degrees) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> table : tables.values()) {
            String schema = str(table.get("schema"));
            String name = str(table.get("name"));
            TableDegree degree = degrees.getOrDefault(key(schema, name), TableDegree.ZERO);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", key(schema, name));
            node.put("schema", schema);
            node.put("table", name);
            node.put("classification", classifyTable(table, degrees));
            node.put("incomingDegree", degree.in);
            node.put("outgoingDegree", degree.out);
            node.put("totalDegree", degree.total());
            node.put("columnCount", columnCount(table));
            node.put("primaryKey", stringList(mapValue(table.get("primaryKey")), "columns"));
            nodes.add(node);
        }
        nodes.sort((a, b) -> Integer.compare(
                ((Number) b.get("totalDegree")).intValue(),
                ((Number) a.get("totalDegree")).intValue()));
        return nodes;
    }

    private List<Map<String, Object>> graphEdges(List<Map<String, Object>> edges) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            Map<String, Object> graphEdge = new LinkedHashMap<>();
            graphEdge.put("relationshipType", edge.get("relationshipType"));
            graphEdge.put("name", edge.get("fkName"));
            graphEdge.put("from", key(str(edge.get("fromSchema")), str(edge.get("fromTable"))));
            graphEdge.put("to", key(str(edge.get("toSchema")), str(edge.get("toTable"))));
            graphEdge.put("fromTable", edge.get("fromTable"));
            graphEdge.put("fromColumns", edge.get("fromColumns"));
            graphEdge.put("toTable", edge.get("toTable"));
            graphEdge.put("toColumns", edge.get("toColumns"));
            if (edge.get("confidence") != null) graphEdge.put("confidence", edge.get("confidence"));
            out.add(graphEdge);
        }
        return out;
    }

    private List<Map<String, Object>> centralTables(List<Map<String, Object>> nodes, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            if (out.size() >= limit) break;
            if (((Number) node.get("totalDegree")).intValue() <= 0) continue;
            Map<String, Object> central = new LinkedHashMap<>();
            central.put("schema", node.get("schema"));
            central.put("table", node.get("table"));
            central.put("classification", node.get("classification"));
            central.put("incomingDegree", node.get("incomingDegree"));
            central.put("outgoingDegree", node.get("outgoingDegree"));
            central.put("totalDegree", node.get("totalDegree"));
            out.add(central);
        }
        return out;
    }

    private List<Map<String, Object>> isolatedTables(List<Map<String, Object>> nodes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            if (((Number) node.get("totalDegree")).intValue() != 0) continue;
            Map<String, Object> isolated = new LinkedHashMap<>();
            isolated.put("schema", node.get("schema"));
            isolated.put("table", node.get("table"));
            isolated.put("classification", node.get("classification"));
            out.add(isolated);
        }
        return out;
    }

    private Map<String, List<String>> undirectedAdjacency(Map<String, Map<String, Object>> tables,
                                                         List<Map<String, Object>> edges) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String tableKey : tables.keySet()) adjacency.put(tableKey, new ArrayList<>());
        for (Map<String, Object> edge : edges) {
            String from = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
            String to = key(str(edge.get("toSchema")), str(edge.get("toTable")));
            if (adjacency.containsKey(from) && adjacency.containsKey(to)) {
                adjacency.get(from).add(to);
                adjacency.get(to).add(from);
            }
        }
        return adjacency;
    }

    private List<Map<String, Object>> connectedComponents(Map<String, Map<String, Object>> tables,
                                                          Map<String, List<String>> adjacency) {
        List<Map<String, Object>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String start : tables.keySet()) {
            if (!visited.add(start)) continue;
            List<String> members = new ArrayList<>();
            Queue<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.remove();
                members.add(current);
                for (String next : adjacency.getOrDefault(current, List.of())) {
                    if (visited.add(next)) queue.add(next);
                }
            }
            members.sort(String::compareToIgnoreCase);
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("size", members.size());
            component.put("tables", members);
            components.add(component);
        }
        components.sort((a, b) -> Integer.compare(
                ((Number) b.get("size")).intValue(),
                ((Number) a.get("size")).intValue()));
        return components;
    }

    private List<Map<String, Object>> cycleHints(Map<String, Map<String, Object>> tables,
                                                 List<Map<String, Object>> edges, int limit) {
        List<Map<String, Object>> cycles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, List<Map<String, Object>>> byFrom = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            byFrom.computeIfAbsent(key(str(edge.get("fromSchema")), str(edge.get("fromTable"))),
                    ignored -> new ArrayList<>()).add(edge);
        }
        for (Map<String, Object> edge : edges) {
            if (cycles.size() >= limit) break;
            String from = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
            String to = key(str(edge.get("toSchema")), str(edge.get("toTable")));
            if (!tables.containsKey(from) || !tables.containsKey(to)) continue;
            if (!hasDirectedPath(to, from, byFrom, 4, Set.of(to))) continue;
            List<String> pair = new ArrayList<>(List.of(from, to));
            pair.sort(String::compareToIgnoreCase);
            String cycleKey = String.join("->", pair);
            if (!seen.add(cycleKey)) continue;
            Map<String, Object> cycle = new LinkedHashMap<>();
            cycle.put("tables", pair);
            cycle.put("note", "Directed relationship cycle detected or implied within 4 hops");
            cycles.add(cycle);
        }
        return cycles;
    }

    private boolean hasDirectedPath(String current, String target,
                                    Map<String, List<Map<String, Object>>> byFrom,
                                    int remainingDepth, Set<String> visited) {
        if (remainingDepth <= 0) return false;
        for (Map<String, Object> edge : byFrom.getOrDefault(current, List.of())) {
            String next = key(str(edge.get("toSchema")), str(edge.get("toTable")));
            if (next.equals(target)) return true;
            if (visited.contains(next)) continue;
            Set<String> nextVisited = new HashSet<>(visited);
            nextVisited.add(next);
            if (hasDirectedPath(next, target, byFrom, remainingDepth - 1, nextVisited)) return true;
        }
        return false;
    }

    private Map<String, Object> shortestGraphPath(String fromKey, String toKey,
                                                  List<Map<String, Object>> edges, int maxDepth) {
        Map<String, List<GraphEdge>> byFrom = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            byFrom.computeIfAbsent(key(str(edge.get("fromSchema")), str(edge.get("fromTable"))),
                    ignored -> new ArrayList<>()).add(GraphEdge.forward(edge));
            byFrom.computeIfAbsent(key(str(edge.get("toSchema")), str(edge.get("toTable"))),
                    ignored -> new ArrayList<>()).add(GraphEdge.reverse(edge));
        }
        List<List<Map<String, Object>>> paths = searchPaths(fromKey, toKey, byFrom, maxDepth, 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromKey);
        out.put("to", toKey);
        out.put("found", !paths.isEmpty());
        out.put("edges", paths.isEmpty() ? List.of() : paths.get(0));
        return out;
    }

    private String resolveTableKey(Map<String, Map<String, Object>> tables, String schema, String table) {
        String exact = key(schema, table);
        if (tables.containsKey(exact)) return exact;
        String normalizedTable = table == null ? "" : table.toLowerCase(Locale.ROOT);
        for (String tableKey : tables.keySet()) {
            if (tableKey.endsWith("." + normalizedTable)) return tableKey;
        }
        return exact;
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

    private List<String> splitCsvInput(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) out.add(item);
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
        return "\"" + id + "\"";
    }

    private String classifyTable(Map<String, Object> table, Map<String, TableDegree> degrees) {
        String name = normalizeIdentifier(str(table.get("name")));
        TableDegree degree = degrees.getOrDefault(key(str(table.get("schema")), str(table.get("name"))), TableDegree.ZERO);
        int columnCount = columnCount(table);
        int fkCount = mapList(table.get("foreignKeys")).size();
        if (name.contains("audit") || name.contains("history") || !mapList(table.get("triggers")).isEmpty()) {
            return "audit/history";
        }
        if (fkCount >= 2 && columnCount <= 6) return "junction/detail";
        if (degree.in >= 2) return "hub";
        if (degree.out >= 1 && degree.in == 0) return "fact/detail";
        if (columnCount <= 5 && degree.in >= 1) return "lookup/reference";
        if (name.endsWith("_type") || name.endsWith("_status") || name.contains("lookup")) {
            return "lookup/reference";
        }
        return "entity";
    }

    private EnumLikeConstraint enumLikeConstraint(Map<String, Object> table, Map<String, Object> constraint) {
        Object definition = constraint.get("definition");
        if (definition == null) return null;
        String def = String.valueOf(definition);
        EnumLikeConstraint pgArray = postgresArrayEnumConstraint(table, def);
        if (pgArray != null) return pgArray;
        int inPos = def.toUpperCase(Locale.ROOT).indexOf(" IN ");
        if (inPos < 0) return null;
        int open = def.indexOf('(', inPos);
        int close = def.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return null;
        String before = def.substring(0, inPos).replace("CHECK", "")
                .replace("(", "").replace("\"", "").trim();
        String column = before.contains(" ") ? before.substring(before.lastIndexOf(' ') + 1) : before;
        if (column.isBlank()) {
            List<String> cols = stringList(constraint, "columns");
            column = cols.isEmpty() ? null : cols.get(0);
        }
        if (column == null || column.isBlank() || columnByName(table, column).isEmpty()) return null;
        String valuesRaw = def.substring(open + 1, close);
        List<String> values = new ArrayList<>();
        for (String part : valuesRaw.split(",")) {
            String value = part.trim().replace("'", "").replace("\"", "");
            if (!value.isEmpty()) values.add(value);
        }
        if (values.size() < 2 || values.size() > 20) return null;
        return new EnumLikeConstraint(column, values);
    }

    private EnumLikeConstraint postgresArrayEnumConstraint(Map<String, Object> table, String definition) {
        String upper = definition.toUpperCase(Locale.ROOT);
        int anyPos = upper.indexOf("= ANY");
        int arrayPos = upper.indexOf("ARRAY[", anyPos);
        if (anyPos < 0 || arrayPos < 0) return null;
        String before = definition.substring(0, anyPos)
                .replace("CHECK", "").replace("(", "").replace("\"", "").trim();
        String column = before.contains(" ") ? before.substring(before.lastIndexOf(' ') + 1) : before;
        if (column.isBlank() || columnByName(table, column).isEmpty()) return null;

        int open = definition.indexOf('[', arrayPos);
        int close = definition.indexOf(']', open + 1);
        if (open < 0 || close < 0 || close <= open) return null;
        List<String> values = new ArrayList<>();
        for (String part : definition.substring(open + 1, close).split(",")) {
            String value = part.trim();
            int cast = value.indexOf("::");
            if (cast >= 0) value = value.substring(0, cast);
            value = value.replace("'", "").replace("\"", "").trim();
            if (!value.isEmpty()) values.add(value);
        }
        if (values.size() < 2 || values.size() > 20) return null;
        return new EnumLikeConstraint(column, values);
    }

    private String qualifiedName(Map<String, Object> table) {
        String schema = str(table.get("schema"));
        String name = str(table.get("name"));
        return schema == null || schema.isBlank() ? name : schema + "." + name;
    }

    private int columnCount(Map<String, Object> table) {
        return mapList(table.get("columns")).size();
    }

    private String relationshipSummary(Map<String, Object> table, Map<String, TableDegree> degrees) {
        TableDegree degree = degrees.getOrDefault(key(str(table.get("schema")), str(table.get("name"))), TableDegree.ZERO);
        return degree.in + " incoming, " + degree.out + " outgoing";
    }

    private String primaryKeySummary(Map<String, Object> table) {
        List<String> pk = stringList(mapValue(table.get("primaryKey")), "columns");
        return pk.isEmpty() ? "(none)" : String.join("+", pk);
    }

    private String topColumns(Map<String, Object> table, int limit) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> column : mapList(table.get("columns"))) {
            if (names.size() >= limit) break;
            names.add(str(column.get("name")));
        }
        return String.join(", ", names);
    }

    private String shorten(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, Math.max(0, limit - 3)) + "...";
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
        out.put("allowedValues", info.get("allowedValues"));
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

    private Set<String> parseChecks(String checks) {
        Set<String> defaults = Set.of("missingPrimaryKey", "fkWithoutIndex", "fkTypeMismatch",
                "inferredRelationship", "nullableUnique", "unconstrainedStatus",
                "orphanIdColumn", "missingRemarks", "isolatedTable", "wideTable");
        if (checks == null || checks.isBlank()) return defaults;
        Set<String> out = new HashSet<>();
        for (String part : checks.split(",")) {
            String check = part.trim();
            if (!check.isEmpty()) out.add(check);
        }
        return out.isEmpty() ? defaults : out;
    }

    private boolean checkEnabled(Set<String> checks, String check) {
        return checks.contains(check) || checks.contains("all");
    }

    private void addFinding(List<Map<String, Object>> findings, int limit,
                            String severity, String check, String schema, String table,
                            String column, String message, String recommendation) {
        if (findings.size() >= limit) return;
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("severity", severity);
        finding.put("check", check);
        finding.put("schema", schema);
        finding.put("table", table);
        if (column != null && !column.isBlank()) finding.put("column", column);
        finding.put("message", message);
        finding.put("recommendation", recommendation);
        findings.add(finding);
    }

    private boolean hasCheckConstraintForColumn(Map<String, Object> info, String columnName) {
        if (columnName == null) return false;
        String normalizedColumn = normalizeIdentifier(columnName);
        for (Map<String, Object> constraint : mapList(info.get("constraints"))) {
            if (!"CHECK".equalsIgnoreCase(str(constraint.get("type")))) continue;
            if (stringList(constraint, "columns").stream()
                    .anyMatch(c -> normalizedColumn.equals(normalizeIdentifier(c)))) {
                if (isNotNullCheck(constraint.get("definition"), normalizedColumn)) continue;
                return true;
            }
            Object definition = constraint.get("definition");
            if (isNotNullCheck(definition, normalizedColumn)) continue;
            if (definition != null
                    && normalizeIdentifier(String.valueOf(definition)).contains(normalizedColumn)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotNullCheck(Object definition, String normalizedColumn) {
        if (definition == null || normalizedColumn == null || normalizedColumn.isBlank()) return false;
        String normalizedDefinition = normalizeIdentifier(String.valueOf(definition));
        return normalizedDefinition.equals(normalizedColumn + "_is_not_null")
                || normalizedDefinition.equals(normalizedColumn + "_not_null");
    }

    private List<List<String>> indexColumns(Map<String, Object> info) {
        List<List<String>> out = new ArrayList<>();
        for (Map<String, Object> index : mapList(info.get("indexes"))) {
            out.add(objectList(index.get("columns")));
        }
        return out;
    }

    private boolean isCoveredByIndex(List<String> columns, List<List<String>> indexes) {
        if (columns.isEmpty()) return true;
        List<String> expected = lowerAll(columns);
        for (List<String> index : indexes) {
            if (index.size() < expected.size()) continue;
            if (lowerAll(index.subList(0, expected.size())).equals(expected)) return true;
        }
        return false;
    }

    private List<String> lowerAll(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String value : values) out.add(value == null ? null : value.toLowerCase(Locale.ROOT));
        return out;
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private List<String> objectList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
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

    private String dotId(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private String dotString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String joinCondition(Map<String, Object> edge) {
        List<String> left = objectList(edge.get("fromColumns"));
        List<String> right = objectList(edge.get("toColumns"));
        String fromTable = str(edge.get("fromTable"));
        String toTable = str(edge.get("toTable"));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            parts.add(fromTable + "." + left.get(i) + " = " + toTable + "." + right.get(i));
        }
        return String.join(" AND ", parts);
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

    private static final class TableDegree {
        static final TableDegree ZERO = new TableDegree();

        int in;
        int out;

        int total() {
            return in + out;
        }
    }

    private record EnumLikeConstraint(String column, List<String> values) {
    }

    private record TableScore(Map<String, Object> table, int score) {
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
