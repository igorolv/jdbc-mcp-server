package ru.it_spectrum.ai.jdbc.mcp.metadata;

import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.DeclaredSchemaEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.ObservedQueryEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

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

abstract class SchemaContextSupport {

    protected static final int DEFAULT_MAX_TABLES = 50;
    protected static final int MAX_TABLES_LIMIT = 300;
    protected static final int DEFAULT_DEPTH = 1;
    protected static final int MAX_DEPTH = 4;
    protected static final int DEFAULT_MAX_PATHS = 5;
    protected static final int MAX_PATHS_LIMIT = 25;
    protected static final int DEFAULT_MAX_FINDINGS = 200;
    protected static final int MAX_FINDINGS_LIMIT = 1_000;

    protected final MetadataService metadata;
    protected final StatsService stats;
    protected final SqlExecutor executor;
    protected final SqlDialect dialect;
    protected final UsageCatalogService usageCatalog;

    protected SchemaContextSupport(MetadataService metadata, StatsService stats,
                                   SqlExecutor executor, SqlDialect dialect,
                                   UsageCatalogService usageCatalog) {
        this.metadata = metadata;
        this.stats = stats;
        this.executor = executor;
        this.dialect = dialect;
        this.usageCatalog = usageCatalog;
    }

    protected Map<String, Map<String, Object>> loadSchemaTables(String schema, int limit) throws SQLException {
        List<TableEntry> listed = metadata.listTables(schema, "%", parseTypes("TABLE"));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        int count = 0;
        for (TableEntry row : listed) {
            if (count >= limit) break;
            String tableSchema = row.schema();
            String tableName = row.name();
            if (tableName == null || tableName.isBlank()) continue;
            Map<String, Object> described = metadata.describeTable(tableSchema, tableName);
            out.put(key(str(described.get("schema")), str(described.get("name"))), described);
            count++;
        }
        return out;
    }

    protected Map<String, Map<String, Object>> loadBriefTables(String schema, String terms, int limit)
            throws SQLException {
        String pattern = terms == null || terms.isBlank() ? "%" : "%" + terms + "%";
        List<TableEntry> listed = metadata.listTables(schema, pattern, parseTypes("TABLE"));
        if (listed.isEmpty() && terms != null && !terms.isBlank()) {
            listed = metadata.listTables(schema, "%", parseTypes("TABLE"));
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        int count = 0;
        for (TableEntry row : listed) {
            if (count >= limit) break;
            String tableSchema = row.schema();
            String tableName = row.name();
            if (tableName == null || tableName.isBlank()) continue;
            Map<String, Object> described = metadata.describeTable(tableSchema, tableName);
            out.put(key(str(described.get("schema")), str(described.get("name"))), described);
            count++;
        }
        return out;
    }

    protected Map<String, Map<String, Object>> loadSingleTable(String schema, String table) throws SQLException {
        Map<String, Object> described = metadata.describeTable(schema, table);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(key(str(described.get("schema")), str(described.get("name"))), described);
        return out;
    }

    protected Map<String, TableDegree> tableDegrees(Map<String, Map<String, Object>> tables,
                                                  List<Map<String, Object>> fkEdges) {
        Map<String, TableDegree> degrees = new HashMap<>();
        for (String tableKey : tables.keySet()) degrees.put(tableKey, new TableDegree());
        for (Map<String, Object> edge : fkEdges) incrementDegrees(degrees, edge);
        return degrees;
    }

    protected void incrementDegrees(Map<String, TableDegree> degrees, Map<String, Object> edge) {
        String from = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
        String to = key(str(edge.get("toSchema")), str(edge.get("toTable")));
        TableDegree fromDegree = degrees.get(from);
        if (fromDegree != null) fromDegree.out++;
        TableDegree toDegree = degrees.get(to);
        if (toDegree != null) toDegree.in++;
    }

    protected List<Map<String, Object>> graphNodes(Map<String, Map<String, Object>> tables,
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

    protected List<Map<String, Object>> graphEdges(List<Map<String, Object>> edges) {
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
            out.add(graphEdge);
        }
        return out;
    }

    protected List<Map<String, Object>> centralTables(List<Map<String, Object>> nodes, int limit) {
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

    protected List<Map<String, Object>> isolatedTables(List<Map<String, Object>> nodes) {
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

    protected Map<String, List<String>> undirectedAdjacency(Map<String, Map<String, Object>> tables,
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

    protected List<Map<String, Object>> connectedComponents(Map<String, Map<String, Object>> tables,
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

    protected List<Map<String, Object>> cycleHints(Map<String, Map<String, Object>> tables,
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

    protected boolean hasDirectedPath(String current, String target,
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

    protected Map<String, Object> shortestGraphPath(String fromKey, String toKey,
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

    protected String resolveTableKey(Map<String, Map<String, Object>> tables, String schema, String table) {
        String exact = key(schema, table);
        if (tables.containsKey(exact)) return exact;
        String normalizedTable = table == null ? "" : table.toLowerCase(Locale.ROOT);
        for (String tableKey : tables.keySet()) {
            if (tableKey.endsWith("." + normalizedTable)) return tableKey;
        }
        return exact;
    }

    protected List<String> splitCsvInput(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    protected String classifyTable(Map<String, Object> table, Map<String, TableDegree> degrees) {
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

    protected int columnCount(Map<String, Object> table) {
        return mapList(table.get("columns")).size();
    }

    protected List<List<Map<String, Object>>> searchPaths(String start, String target,
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

    protected Map<String, Object> compactTable(Map<String, Object> info, boolean includeStats) throws SQLException {
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

    protected List<Map<String, Object>> compactIndexes(Object indexesValue) {
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

    protected boolean isView(Map<String, Object> info) {
        String type = str(info.get("type"));
        return type != null && type.toUpperCase(Locale.ROOT).contains("VIEW");
    }

    protected List<Neighbor> neighbors(Map<String, Object> info, boolean includeIncoming) {
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

    protected List<Map<String, Object>> outgoingEdges(Map<String, Object> info) {
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

    protected List<Map<String, Object>> incomingEdges(Map<String, Object> info) {
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

    /**
     * Decorates each edge with a typed three-layer {@link RelationshipEvidence} bundle and, when
     * {@code includeObserved} is true and the local usage catalog is enabled, appends any
     * observed-only equi-join pairs that don't match a declared FK.
     *
     * <p>The {@code evidence} block on each edge carries up to three layers:
     * <ul>
     *   <li>{@code declaredSchema} — present iff the edge originates from a database catalog FK
     *       (highest structural evidence).</li>
     *   <li>{@code observedQuery} — present iff the (table, column) pair appears as an equi-join
     *       in stored application queries; carries {@code joinSupport} and a capped uid list.</li>
     *   <li>{@code semanticUsage} — overlap of business domains / business objects / output
     *       labels across queries that touch <i>both</i> tables. This is decoration only — it
     *       never introduces new edges.</li>
     * </ul>
     *
     * <p>Matching against observed pairs is undirected and single-column only; composite FKs
     * still receive a declared layer but no observed-pair match in this iteration.
     */
    protected void decorateAndAppendObserved(List<Map<String, Object>> edges,
                                             Set<String> describedTableNamesUpper,
                                             boolean includeObserved) {
        Map<Map<String, Object>, RelationshipEvidence.Builder> builders = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            RelationshipEvidence.Builder b = builderFor(edge, builders);
            if ("foreignKey".equals(str(edge.get("relationshipType")))) {
                b.declaredSchema = new DeclaredSchemaEdgeEvidence(
                        str(edge.get("fkName")),
                        objectList(edge.get("fromColumns")),
                        objectList(edge.get("toColumns")));
            }
        }
        if (!includeObserved || usageCatalog == null || !usageCatalog.enabled()) {
            applyEvidence(edges, builders);
            return;
        }

        Set<String> tableScope = new HashSet<>();
        if (describedTableNamesUpper != null) tableScope.addAll(describedTableNamesUpper);
        for (Map<String, Object> edge : edges) {
            String from = upper(str(edge.get("fromTable")));
            String to = upper(str(edge.get("toTable")));
            if (from != null) tableScope.add(from);
            if (to != null) tableScope.add(to);
        }
        if (tableScope.isEmpty()) {
            applyEvidence(edges, builders);
            return;
        }

        List<UsageCatalogService.ObservedEdge> observed = usageCatalog.observedEdges(tableScope, 1);
        Map<String, UsageCatalogService.ObservedEdge> byPair = new HashMap<>();
        for (UsageCatalogService.ObservedEdge oe : observed) {
            byPair.putIfAbsent(undirectedPairKey(
                    oe.leftTable(), oe.leftColumn(), oe.rightTable(), oe.rightColumn()), oe);
        }
        Set<String> consumed = new HashSet<>();
        for (Map<String, Object> edge : edges) {
            List<?> fromCols = edge.get("fromColumns") instanceof List<?> fl ? fl : List.of();
            List<?> toCols = edge.get("toColumns") instanceof List<?> tl ? tl : List.of();
            if (fromCols.size() != 1 || toCols.size() != 1) continue;
            String key = undirectedPairKey(
                    str(edge.get("fromTable")), String.valueOf(fromCols.get(0)),
                    str(edge.get("toTable")), String.valueOf(toCols.get(0)));
            UsageCatalogService.ObservedEdge oe = byPair.get(key);
            if (oe == null) continue;
            builderFor(edge, builders).observedQuery = new ObservedQueryEdgeEvidence(
                    oe.support(), capQueryUids(oe.queryUids()));
            consumed.add(key);
        }

        Set<String> describedScope = describedTableNamesUpper == null ? Set.of() : describedTableNamesUpper;
        for (UsageCatalogService.ObservedEdge oe : observed) {
            String key = undirectedPairKey(oe.leftTable(), oe.leftColumn(), oe.rightTable(), oe.rightColumn());
            if (consumed.contains(key)) continue;
            String leftUpper = upper(oe.leftTable());
            String rightUpper = upper(oe.rightTable());
            if (!describedScope.isEmpty()
                    && (!describedScope.contains(leftUpper) || !describedScope.contains(rightUpper))) {
                continue;
            }
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("relationshipType", "observed");
            edge.put("fkName", "observed_" + oe.leftTable() + "_" + oe.leftColumn()
                    + "_to_" + oe.rightTable() + "_" + oe.rightColumn());
            edge.put("fromSchema", oe.leftSchema());
            edge.put("fromTable", oe.leftTable());
            edge.put("fromColumns", List.of(oe.leftColumn()));
            edge.put("toSchema", oe.rightSchema());
            edge.put("toTable", oe.rightTable());
            edge.put("toColumns", List.of(oe.rightColumn()));
            edge.put("undirected", true);
            edges.add(edge);
            RelationshipEvidence.Builder b = builderFor(edge, builders);
            b.observedQuery = new ObservedQueryEdgeEvidence(oe.support(), capQueryUids(oe.queryUids()));
        }

        for (Map.Entry<Map<String, Object>, RelationshipEvidence.Builder> entry : builders.entrySet()) {
            Map<String, Object> edge = entry.getKey();
            RelationshipEvidence.Builder b = entry.getValue();
            if (b.declaredSchema == null && b.observedQuery == null) continue;
            SemanticEdgeEvidence semantic = usageCatalog.semanticEdgeEvidence(
                    str(edge.get("fromSchema")), str(edge.get("fromTable")),
                    str(edge.get("toSchema")), str(edge.get("toTable")));
            if (semantic != null && !semantic.isEmpty()) {
                b.semanticUsage = semantic;
            }
        }

        applyEvidence(edges, builders);
    }

    private static RelationshipEvidence.Builder builderFor(
            Map<String, Object> edge,
            Map<Map<String, Object>, RelationshipEvidence.Builder> builders) {
        return builders.computeIfAbsent(edge, ignored -> new RelationshipEvidence.Builder());
    }

    private static void applyEvidence(List<Map<String, Object>> edges,
                                      Map<Map<String, Object>, RelationshipEvidence.Builder> builders) {
        for (Map<String, Object> edge : edges) {
            RelationshipEvidence.Builder b = builders.get(edge);
            if (b == null) continue;
            RelationshipEvidence re = b.build();
            if (re.isEmpty()) continue;
            edge.put("evidence", re.toMap());
        }
    }

    private static List<String> capQueryUids(List<String> uids) {
        if (uids == null || uids.size() <= 5) return uids == null ? List.of() : List.copyOf(uids);
        return List.copyOf(uids.subList(0, 5));
    }

    private static String undirectedPairKey(String tableA, String columnA, String tableB, String columnB) {
        String a = upper(tableA) + "." + upper(columnA);
        String b = upper(tableB) + "." + upper(columnB);
        return a.compareTo(b) <= 0 ? a + "==" + b : b + "==" + a;
    }

    protected static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    protected boolean defaultIncludeObserved(Boolean explicit) {
        if (explicit != null) return explicit;
        return usageCatalog != null && usageCatalog.enabled();
    }

    protected Map<String, Object> columnByName(Map<String, Object> table, String columnName) {
        for (Map<String, Object> column : mapList(table.get("columns"))) {
            if (columnName.equalsIgnoreCase(str(column.get("name")))) {
                return column;
            }
        }
        return Map.of();
    }

    protected boolean isKnownKeyColumn(Map<String, Object> table, String columnName) {
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

    protected String referenceNameFromColumn(String columnName) {
        String normalized = normalizeIdentifier(columnName);
        if (!normalized.endsWith("_id") || normalized.length() <= 3) return null;
        return normalized.substring(0, normalized.length() - 3);
    }

    protected boolean typesCompatible(String leftType, String rightType) {
        if (leftType == null || rightType == null) return true;
        String left = typeFamily(leftType);
        String right = typeFamily(rightType);
        return left.equals(right);
    }

    protected String typeFamily(String typeName) {
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

    protected boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    protected List<String> objectList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    protected String normalizeIdentifier(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "").replaceAll("_+$", "");
    }

    protected String singular(String value) {
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

    protected void addUnique(List<Map<String, Object>> target, Set<String> seen, Map<String, Object> edge) {
        String k = edgeKey(edge);
        if (seen.add(k)) target.add(edge);
    }

    protected String edgeKey(Map<String, Object> edge) {
        return key(str(edge.get("fromSchema")), str(edge.get("fromTable"))) + ":"
                + edge.get("fromColumns") + "->"
                + key(str(edge.get("toSchema")), str(edge.get("toTable"))) + ":"
                + edge.get("toColumns");
    }

    protected String[] parseTypes(String types) {
        if (types == null || types.isBlank()) return null;
        String[] raw = types.split(",");
        List<String> cleaned = new ArrayList<>();
        for (String part : raw) {
            String type = part.trim();
            if (!type.isEmpty()) cleaned.add(type);
        }
        return cleaned.toArray(new String[0]);
    }

    protected int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) return defaultValue;
        return Math.max(min, Math.min(max, value));
    }

    protected String key(String schema, String table) {
        return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "."
                + (table == null ? "" : table.toLowerCase(Locale.ROOT));
    }

    protected String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> mapList(Object value) {
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
    protected Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    protected List<String> stringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) out.add(String.valueOf(item));
        }
        return out;
    }

    protected String dotId(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    protected String dotString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    protected String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    protected String joinCondition(Map<String, Object> edge) {
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

    protected record Neighbor(String schema, String table) {
    }

    protected record NodeDepth(String schema, String table, int depth) {
    }

    protected record PathState(String node, List<Map<String, Object>> edges, Set<String> visited) {
    }

    protected static final class TableDegree {
        static final TableDegree ZERO = new TableDegree();

        int in;
        int out;

        int total() {
            return in + out;
        }
    }

    protected record TableScore(Map<String, Object> table, int score) {
    }

    protected record GraphEdge(String direction, String relationshipType, String fkName,
                               String fromSchema, String fromTable, Object fromColumns,
                               String toSchema, String toTable, Object toColumns,
                               Object evidence) {

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
                    edge.get("evidence"));
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
                    edge.get("evidence"));
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
            if (evidence != null) out.put("evidence", evidence);
            return out;
        }

        protected String joinCondition() {
            List<String> left = objectList(fromColumns);
            List<String> right = objectList(toColumns);
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                parts.add(qualified(fromTable, left.get(i)) + " = " + qualified(toTable, right.get(i)));
            }
            return String.join(" AND ", parts);
        }

        protected static String qualified(String table, String column) {
            return table + "." + column;
        }

        protected static List<String> objectList(Object value) {
            if (!(value instanceof List<?> list)) return List.of();
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }

        protected static String strStatic(Object value) {
            return Objects.toString(value, null);
        }
    }
}
