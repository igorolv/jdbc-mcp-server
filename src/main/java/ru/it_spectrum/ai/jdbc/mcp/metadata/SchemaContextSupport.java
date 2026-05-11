package ru.it_spectrum.ai.jdbc.mcp.metadata;

import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.CompactTable;
import ru.it_spectrum.ai.jdbc.mcp.model.context.CycleHint;
import ru.it_spectrum.ai.jdbc.mcp.model.context.GraphComponent;
import ru.it_spectrum.ai.jdbc.mcp.model.context.GraphEdgeSummary;
import ru.it_spectrum.ai.jdbc.mcp.model.context.GraphNode;
import ru.it_spectrum.ai.jdbc.mcp.model.context.GraphTableSummary;
import ru.it_spectrum.ai.jdbc.mcp.model.context.JoinPathStep;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.model.context.ShortestPath;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.DeclaredSchemaEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.ObservedQueryEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.IncomingForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.TableStats;
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

    protected Map<String, TableDescription> loadSchemaTables(String schema, int limit) throws SQLException {
        List<TableEntry> listed = metadata.listTables(schema, "%", parseTypes("TABLE"));
        List<TableEntry> selected = listed.subList(0, Math.min(limit, listed.size()));
        return metadata.describeTables(schema, selected);
    }

    protected Map<String, TableDescription> loadBriefTables(String schema, String terms, int limit)
            throws SQLException {
        String pattern = terms == null || terms.isBlank() ? "%" : "%" + terms + "%";
        List<TableEntry> listed = metadata.listTables(schema, pattern, parseTypes("TABLE"));
        if (listed.isEmpty() && terms != null && !terms.isBlank()) {
            listed = metadata.listTables(schema, "%", parseTypes("TABLE"));
        }
        List<TableEntry> selected = listed.subList(0, Math.min(limit, listed.size()));
        return metadata.describeTables(schema, selected);
    }

    protected Map<String, TableDescription> loadSingleTable(String schema, String table) throws SQLException {
        TableDescription described = metadata.describeTable(schema, table);
        Map<String, TableDescription> out = new LinkedHashMap<>();
        out.put(key(described.schema(), described.name()), described);
        return out;
    }

    protected Map<String, TableDegree> tableDegrees(Map<String, TableDescription> tables,
                                                  List<RelationshipEdge> fkEdges) {
        Map<String, TableDegree> degrees = new HashMap<>();
        for (String tableKey : tables.keySet()) degrees.put(tableKey, new TableDegree());
        for (RelationshipEdge edge : fkEdges) incrementDegrees(degrees, edge);
        return degrees;
    }

    protected void incrementDegrees(Map<String, TableDegree> degrees, RelationshipEdge edge) {
        String from = key(edge.fromSchema(), edge.fromTable());
        String to = key(edge.toSchema(), edge.toTable());
        TableDegree fromDegree = degrees.get(from);
        if (fromDegree != null) fromDegree.out++;
        TableDegree toDegree = degrees.get(to);
        if (toDegree != null) toDegree.in++;
    }

    protected List<GraphNode> graphNodes(Map<String, TableDescription> tables,
                                                 Map<String, TableDegree> degrees) {
        List<GraphNode> nodes = new ArrayList<>();
        for (TableDescription table : tables.values()) {
            String schema = table.schema();
            String name = table.name();
            TableDegree degree = degrees.getOrDefault(key(schema, name), TableDegree.ZERO);
            nodes.add(new GraphNode(
                    key(schema, name),
                    schema,
                    name,
                    classifyTable(table, degrees),
                    degree.in,
                    degree.out,
                    degree.total(),
                    columnCount(table),
                    table.primaryKey() != null ? table.primaryKey().columns() : List.of()));
        }
        nodes.sort((a, b) -> Integer.compare(b.totalDegree(), a.totalDegree()));
        return nodes;
    }

    protected List<GraphEdgeSummary> graphEdges(List<RelationshipEdge> edges) {
        List<GraphEdgeSummary> out = new ArrayList<>();
        for (RelationshipEdge edge : edges) {
            out.add(new GraphEdgeSummary(
                    edge.relationshipType(),
                    edge.name(),
                    key(edge.fromSchema(), edge.fromTable()),
                    key(edge.toSchema(), edge.toTable()),
                    edge.fromTable(),
                    edge.fromColumns(),
                    edge.toTable(),
                    edge.toColumns()));
        }
        return out;
    }

    protected List<GraphTableSummary> centralTables(List<GraphNode> nodes, int limit) {
        List<GraphTableSummary> out = new ArrayList<>();
        for (GraphNode node : nodes) {
            if (out.size() >= limit) break;
            if (node.totalDegree() <= 0) continue;
            out.add(GraphTableSummary.central(node));
        }
        return out;
    }

    protected List<GraphTableSummary> isolatedTables(List<GraphNode> nodes) {
        List<GraphTableSummary> out = new ArrayList<>();
        for (GraphNode node : nodes) {
            if (node.totalDegree() != 0) continue;
            out.add(GraphTableSummary.isolated(node));
        }
        return out;
    }

    protected Map<String, List<String>> undirectedAdjacency(Map<String, TableDescription> tables,
                                                         List<RelationshipEdge> edges) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String tableKey : tables.keySet()) adjacency.put(tableKey, new ArrayList<>());
        for (RelationshipEdge edge : edges) {
            String from = key(edge.fromSchema(), edge.fromTable());
            String to = key(edge.toSchema(), edge.toTable());
            if (adjacency.containsKey(from) && adjacency.containsKey(to)) {
                adjacency.get(from).add(to);
                adjacency.get(to).add(from);
            }
        }
        return adjacency;
    }

    protected List<GraphComponent> connectedComponents(Map<String, TableDescription> tables,
                                                          Map<String, List<String>> adjacency) {
        List<GraphComponent> components = new ArrayList<>();
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
            components.add(new GraphComponent(members.size(), members));
        }
        components.sort((a, b) -> Integer.compare(b.size(), a.size()));
        return components;
    }

    protected List<CycleHint> cycleHints(Map<String, TableDescription> tables,
                                                 List<RelationshipEdge> edges, int limit) {
        List<CycleHint> cycles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, List<RelationshipEdge>> byFrom = new HashMap<>();
        for (RelationshipEdge edge : edges) {
            byFrom.computeIfAbsent(key(edge.fromSchema(), edge.fromTable()),
                    ignored -> new ArrayList<>()).add(edge);
        }
        for (RelationshipEdge edge : edges) {
            if (cycles.size() >= limit) break;
            String from = key(edge.fromSchema(), edge.fromTable());
            String to = key(edge.toSchema(), edge.toTable());
            if (!tables.containsKey(from) || !tables.containsKey(to)) continue;
            if (!hasDirectedPath(to, from, byFrom, 4, Set.of(to))) continue;
            List<String> pair = new ArrayList<>(List.of(from, to));
            pair.sort(String::compareToIgnoreCase);
            String cycleKey = String.join("->", pair);
            if (!seen.add(cycleKey)) continue;
            cycles.add(new CycleHint(pair, "Directed relationship cycle detected or implied within 4 hops"));
        }
        return cycles;
    }

    protected boolean hasDirectedPath(String current, String target,
                                    Map<String, List<RelationshipEdge>> byFrom,
                                    int remainingDepth, Set<String> visited) {
        if (remainingDepth <= 0) return false;
        for (RelationshipEdge edge : byFrom.getOrDefault(current, List.of())) {
            String next = key(edge.toSchema(), edge.toTable());
            if (next.equals(target)) return true;
            if (visited.contains(next)) continue;
            Set<String> nextVisited = new HashSet<>(visited);
            nextVisited.add(next);
            if (hasDirectedPath(next, target, byFrom, remainingDepth - 1, nextVisited)) return true;
        }
        return false;
    }

    protected ShortestPath shortestGraphPath(String fromKey, String toKey,
                                                  List<RelationshipEdge> edges, int maxDepth) {
        Map<String, List<GraphEdge>> byFrom = new HashMap<>();
        for (RelationshipEdge edge : edges) {
            byFrom.computeIfAbsent(key(edge.fromSchema(), edge.fromTable()),
                    ignored -> new ArrayList<>()).add(GraphEdge.forward(edge));
            byFrom.computeIfAbsent(key(edge.toSchema(), edge.toTable()),
                    ignored -> new ArrayList<>()).add(GraphEdge.reverse(edge));
        }
        List<List<JoinPathStep>> paths = searchPaths(fromKey, toKey, byFrom, maxDepth, 1);
        return new ShortestPath(fromKey, toKey, !paths.isEmpty(), paths.isEmpty() ? List.of() : paths.getFirst());
    }

    protected String resolveTableKey(Map<String, TableDescription> tables, String schema, String table) {
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

    protected String classifyTable(TableDescription table, Map<String, TableDegree> degrees) {
        String name = normalizeIdentifier(table.name());
        TableDegree degree = degrees.getOrDefault(key(table.schema(), table.name()), TableDegree.ZERO);
        int columnCount = columnCount(table);
        int fkCount = table.foreignKeys().size();
        if (name.contains("audit") || name.contains("history") || !table.triggers().isEmpty()) {
            return "audit/history";
        }
        if (fkCount >= 2 && columnCount <= 6) return "junction/detail";
        if (degree.in >= 2) return "hub";
        if (degree.out >= 1 && degree.in == 0) return "fact/detail";
        if (columnCount <= 5 && degree.in == 1) return "lookup/reference";
        if (name.endsWith("_type") || name.endsWith("_status") || name.contains("lookup")) {
            return "lookup/reference";
        }
        return "entity";
    }

    protected int columnCount(TableDescription table) {
        return table.columns().size();
    }

    protected List<List<JoinPathStep>> searchPaths(String start, String target,
                                                        Map<String, List<GraphEdge>> byFrom,
                                                        int maxDepth, int maxPaths) {
        List<List<JoinPathStep>> paths = new ArrayList<>();
        Queue<PathState> queue = new ArrayDeque<>();
        queue.add(new PathState(start, List.of(), Set.of(start)));

        while (!queue.isEmpty() && paths.size() < maxPaths) {
            PathState state = queue.remove();
            if (state.edges.size() >= maxDepth) continue;
            for (GraphEdge edge : byFrom.getOrDefault(state.node, List.of())) {
                String next = key(edge.toSchema, edge.toTable);
                if (state.visited.contains(next)) continue;
                List<JoinPathStep> nextEdges = new ArrayList<>(state.edges);
                nextEdges.add(edge.asStep());
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

    protected CompactTable compactTable(TableDescription info, boolean includeStats) throws SQLException {
        String schema = info.schema();
        String table = info.name();
        Set<String> pkColumns = info.primaryKey() != null
                ? new HashSet<>(info.primaryKey().columns()) : Set.of();
        Set<String> fkColumns = new HashSet<>();
        for (ForeignKey fk : info.foreignKeys()) {
            fkColumns.addAll(fk.columns());
        }
        Set<String> indexedColumns = new HashSet<>();
        for (Index index : info.indexes()) {
            indexedColumns.addAll(index.columns());
        }

        List<CompactTable.CompactColumn> columns = new ArrayList<>();
        for (Column col : info.columns()) {
            String name = col.name();
            columns.add(new CompactTable.CompactColumn(
                    name,
                    col.typeName(),
                    col.nullable(),
                    pkColumns.contains(name) ? Boolean.TRUE : null,
                    fkColumns.contains(name) ? Boolean.TRUE : null,
                    indexedColumns.contains(name) ? Boolean.TRUE : null));
        }

        TableStats tableStats = null;
        if (includeStats && table != null && !isView(info)) {
            try {
                tableStats = stats.tableStats(schema, table);
            } catch (SQLException e) {
                tableStats = TableStats.error(schema, table, e.getMessage());
            }
        }

        return new CompactTable(
                schema,
                table,
                info.type(),
                info.remarks(),
                columns,
                info.primaryKey(),
                info.constraints(),
                info.allowedValues(),
                info.foreignKeys(),
                compactIndexes(info.indexes()),
                info.triggers(),
                tableStats,
                null);
    }

    protected List<CompactTable.CompactIndex> compactIndexes(List<Index> indexes) {
        List<CompactTable.CompactIndex> out = new ArrayList<>();
        for (Index index : indexes) {
            out.add(new CompactTable.CompactIndex(index.name(), index.unique(), index.columns()));
        }
        return out;
    }

    protected boolean isView(TableDescription info) {
        String type = info.type();
        return type != null && type.toUpperCase(Locale.ROOT).contains("VIEW");
    }

    protected List<Neighbor> neighbors(TableDescription info, boolean includeIncoming) {
        List<Neighbor> out = new ArrayList<>();
        for (ForeignKey fk : info.foreignKeys()) {
            out.add(new Neighbor(fk.referencedSchema(), fk.referencedTable()));
        }
        if (includeIncoming) {
            for (IncomingForeignKey fk : info.referencedBy()) {
                out.add(new Neighbor(fk.fromSchema(), fk.fromTable()));
            }
        }
        out.removeIf(n -> n.table == null || n.table.isBlank());
        return out;
    }

    protected List<RelationshipEdge> outgoingEdges(TableDescription info) {
        String schema = info.schema();
        String table = info.name();
        List<RelationshipEdge> out = new ArrayList<>();
        for (ForeignKey fk : info.foreignKeys()) {
            out.add(new RelationshipEdge(
                    "foreignKey", fk.name(), schema, table, fk.columns(),
                    fk.referencedSchema(), fk.referencedTable(), fk.referencedColumns(),
                    null, null));
        }
        return out;
    }

    protected List<RelationshipEdge> incomingEdges(TableDescription info) {
        String schema = info.schema();
        String table = info.name();
        List<RelationshipEdge> out = new ArrayList<>();
        for (IncomingForeignKey fk : info.referencedBy()) {
            out.add(new RelationshipEdge(
                    "foreignKey", fk.name(), fk.fromSchema(), fk.fromTable(), fk.fromColumns(),
                    schema, table, fk.toColumns(), null, null));
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
    protected void decorateAndAppendObserved(List<RelationshipEdge> edges,
                                             Set<String> describedTableNamesUpper,
                                             boolean includeObserved) {
        Map<RelationshipEdge, RelationshipEvidence.Builder> builders = new LinkedHashMap<>();
        for (RelationshipEdge edge : edges) {
            RelationshipEvidence.Builder b = builderFor(edge, builders);
            if ("foreignKey".equals(edge.relationshipType())) {
                b.declaredSchema = new DeclaredSchemaEdgeEvidence(
                        edge.name(),
                        edge.fromColumns() == null ? List.of() : edge.fromColumns(),
                        edge.toColumns() == null ? List.of() : edge.toColumns());
            }
        }
        if (!includeObserved || usageCatalog == null || !usageCatalog.enabled()) {
            applyEvidence(edges, builders);
            return;
        }

        Set<String> tableScope = new HashSet<>();
        if (describedTableNamesUpper != null) tableScope.addAll(describedTableNamesUpper);
        for (RelationshipEdge edge : edges) {
            String from = upper(edge.fromTable());
            String to = upper(edge.toTable());
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
        for (RelationshipEdge edge : edges) {
            List<String> fromCols = edge.fromColumns() == null ? List.of() : edge.fromColumns();
            List<String> toCols = edge.toColumns() == null ? List.of() : edge.toColumns();
            if (fromCols.size() != 1 || toCols.size() != 1) continue;
            String key = undirectedPairKey(
                    edge.fromTable(), fromCols.getFirst(),
                    edge.toTable(), toCols.getFirst());
            UsageCatalogService.ObservedEdge oe = byPair.get(key);
            if (oe == null) continue;
            builderFor(edge, builders).observedQuery = new ObservedQueryEdgeEvidence(
                    oe.support(), capSourceRefs(oe.sourceRefs()));
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
            RelationshipEdge edge = new RelationshipEdge(
                    "observed",
                    "observed_" + oe.leftTable() + "_" + oe.leftColumn()
                            + "_to_" + oe.rightTable() + "_" + oe.rightColumn(),
                    oe.leftSchema(), oe.leftTable(), List.of(oe.leftColumn()),
                    oe.rightSchema(), oe.rightTable(), List.of(oe.rightColumn()),
                    true, null);
            edges.add(edge);
            RelationshipEvidence.Builder b = builderFor(edge, builders);
            b.observedQuery = new ObservedQueryEdgeEvidence(oe.support(), capSourceRefs(oe.sourceRefs()));
        }

        for (Map.Entry<RelationshipEdge, RelationshipEvidence.Builder> entry : builders.entrySet()) {
            RelationshipEdge edge = entry.getKey();
            RelationshipEvidence.Builder b = entry.getValue();
            if (b.declaredSchema == null && b.observedQuery == null) continue;
            SemanticEdgeEvidence semantic = usageCatalog.semanticEdgeEvidence(
                    edge.fromSchema(), edge.fromTable(),
                    edge.toSchema(), edge.toTable());
            if (semantic != null && !semantic.isEmpty()) {
                b.semanticUsage = semantic;
            }
        }

        applyEvidence(edges, builders);
    }

    private static RelationshipEvidence.Builder builderFor(
            RelationshipEdge edge,
            Map<RelationshipEdge, RelationshipEvidence.Builder> builders) {
        return builders.computeIfAbsent(edge, ignored -> new RelationshipEvidence.Builder());
    }

    private static void applyEvidence(List<RelationshipEdge> edges,
                                      Map<RelationshipEdge, RelationshipEvidence.Builder> builders) {
        for (int i = 0; i < edges.size(); i++) {
            RelationshipEdge edge = edges.get(i);
            RelationshipEvidence.Builder b = builders.get(edge);
            if (b == null) continue;
            RelationshipEvidence re = b.build();
            if (re.isEmpty()) continue;
            edges.set(i, edge.withEvidence(re));
        }
    }

    private static List<ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef> capSourceRefs(
            List<ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef> refs) {
        if (refs == null || refs.size() <= 5) return refs == null ? List.of() : List.copyOf(refs);
        return List.copyOf(refs.subList(0, 5));
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

    protected Column columnByName(TableDescription table, String columnName) {
        for (Column column : table.columns()) {
            if (columnName.equalsIgnoreCase(column.name())) {
                return column;
            }
        }
        return null;
    }

    protected boolean isKnownKeyColumn(TableDescription table, String columnName) {
        if (table.primaryKey() != null
                && table.primaryKey().columns().stream().anyMatch(c -> c.equalsIgnoreCase(columnName))) {
            return true;
        }
        for (ForeignKey fk : table.foreignKeys()) {
            if (fk.columns().stream().anyMatch(c -> c.equalsIgnoreCase(columnName))) {
                return true;
            }
        }
        return false;
    }

    protected String referenceNameFromColumn(String columnName) {
        String normalized = normalizeIdentifier(columnName);
        if (!normalized.endsWith("_id") || normalized.length() == 3) return null;
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

    protected void addUnique(List<RelationshipEdge> target, Set<String> seen, RelationshipEdge edge) {
        String k = edgeKey(edge);
        if (seen.add(k)) target.add(edge);
    }

    protected String edgeKey(RelationshipEdge edge) {
        return key(edge.fromSchema(), edge.fromTable()) + ":"
                + edge.fromColumns() + "->"
                + key(edge.toSchema(), edge.toTable()) + ":"
                + edge.toColumns();
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
        return Math.clamp(value, min, max);
    }

    protected String key(String schema, String table) {
        return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "."
                + (table == null ? "" : table.toLowerCase(Locale.ROOT));
    }

    protected String str(Object value) {
        return value == null ? null : String.valueOf(value);
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

    protected String joinCondition(RelationshipEdge edge) {
        List<String> left = edge.fromColumns() == null ? List.of() : edge.fromColumns();
        List<String> right = edge.toColumns() == null ? List.of() : edge.toColumns();
        String fromTable = edge.fromTable();
        String toTable = edge.toTable();
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

    protected record PathState(String node, List<JoinPathStep> edges, Set<String> visited) {
    }

    protected static final class TableDegree {
        static final TableDegree ZERO = new TableDegree();

        int in;
        int out;

        int total() {
            return in + out;
        }
    }

    protected record TableScore(TableDescription table, int score) {
    }

    protected record GraphEdge(String direction, String relationshipType, String fkName,
                               String fromSchema, String fromTable, List<String> fromColumns,
                               String toSchema, String toTable, List<String> toColumns,
                               RelationshipEvidence evidence) {

        static GraphEdge forward(RelationshipEdge edge) {
            return new GraphEdge("forward",
                    edge.relationshipType(),
                    edge.name(),
                    edge.fromSchema(),
                    edge.fromTable(),
                    edge.fromColumns(),
                    edge.toSchema(),
                    edge.toTable(),
                    edge.toColumns(),
                    edge.evidence());
        }

        static GraphEdge reverse(RelationshipEdge edge) {
            return new GraphEdge("reverse",
                    edge.relationshipType(),
                    edge.name(),
                    edge.toSchema(),
                    edge.toTable(),
                    edge.toColumns(),
                    edge.fromSchema(),
                    edge.fromTable(),
                    edge.fromColumns(),
                    edge.evidence());
        }

        JoinPathStep asStep() {
            return new JoinPathStep(direction, relationshipType, fkName,
                    fromSchema, fromTable, fromColumns,
                    toSchema, toTable, toColumns,
                    joinCondition(), evidence);
        }

        protected String joinCondition() {
            List<String> left = fromColumns == null ? List.of() : fromColumns;
            List<String> right = toColumns == null ? List.of() : toColumns;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                parts.add(qualified(fromTable, left.get(i)) + " = " + qualified(toTable, right.get(i)));
            }
            return String.join(" AND ", parts);
        }

        protected static String qualified(String table, String column) {
            return table + "." + column;
        }
    }
}
