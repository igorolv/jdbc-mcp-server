package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaGraph;
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
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

@Service
class SchemaGraphService extends SchemaContextSupport {

    SchemaGraphService(MetadataService metadata, StatsService stats, SqlExecutor executor,
                       SqlDialect dialect, UsageCatalogService usageCatalog) {
        super(metadata, stats, executor, dialect, usageCatalog);
    }

    public SchemaGraph schemaGraph(String schema, Integer maxTables,
                                           String fromTable, String toTable,
                                           Integer maxDepth) throws SQLException {
        int tableLimit = clamp(maxTables, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        int depthLimit = clamp(maxDepth, MAX_DEPTH, 1, MAX_DEPTH);

        Map<String, TableDescription> tables = loadSchemaTables(schema, tableLimit);
        List<Map<String, Object>> declaredEdges = new ArrayList<>();
        for (TableDescription info : tables.values()) declaredEdges.addAll(outgoingEdges(info));

        Map<String, TableDegree> degrees = tableDegrees(tables, declaredEdges);
        Map<String, List<String>> adjacency = undirectedAdjacency(tables, declaredEdges);
        List<Map<String, Object>> nodes = graphNodes(tables, degrees);
        List<Map<String, Object>> components = connectedComponents(tables, adjacency);
        List<Map<String, Object>> cycles = cycleHints(tables, declaredEdges, 25);

        Map<String, Object> shortestPath = null;
        if (fromTable != null && !fromTable.isBlank() && toTable != null && !toTable.isBlank()) {
            String fromKey = resolveTableKey(tables, schema, fromTable);
            String toKey = resolveTableKey(tables, schema, toTable);
            shortestPath = shortestGraphPath(fromKey, toKey, declaredEdges, depthLimit);
        }

        return new SchemaGraph(schema, tables.size(), nodes.size(),
                declaredEdges.size(), declaredEdges.size(),
                centralTables(nodes, 10), isolatedTables(nodes),
                components, cycles, nodes, graphEdges(declaredEdges), shortestPath);
    }

    public String schemaGraphDot(String schema, String tables) throws SQLException {
        int tableLimit = clamp(null, DEFAULT_MAX_TABLES, 1, MAX_TABLES_LIMIT);
        List<String> filterTables = splitCsvInput(tables);

        Map<String, TableDescription> allTables = loadSchemaTables(schema, tableLimit);
        Map<String, TableDescription> selected;
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
        for (TableDescription info : selected.values()) {
            declaredEdges.addAll(outgoingEdges(info));
        }
        declaredEdges.removeIf(e -> !selected.containsKey(key(str(e.get("fromSchema")), str(e.get("fromTable"))))
                || !selected.containsKey(key(str(e.get("toSchema")), str(e.get("toTable")))));

        Map<String, Set<String>> pkCols = new HashMap<>();
        Map<String, Set<String>> fkCols = new HashMap<>();
        for (Map<String, Object> edge : declaredEdges) {
            String fromKey = key(str(edge.get("fromSchema")), str(edge.get("fromTable")));
            for (String col : stringList(edge, "fromColumns")) {
                fkCols.computeIfAbsent(fromKey, k -> new HashSet<>()).add(col);
            }
        }
        for (TableDescription info : selected.values()) {
            String tableKey = key(info.schema(), info.name());
            for (String pkCol : stringList(mapValue(info.primaryKey()), "columns")) {
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

        for (TableDescription info : selected.values()) {
            String tableSchema = info.schema();
            String tableName = info.name();
            String tableKey = key(tableSchema, tableName);
            Set<String> pk = pkCols.getOrDefault(tableKey, Set.of());
            Set<String> fk = fkCols.getOrDefault(tableKey, Set.of());

            sb.append("  ").append(dotId(tableKey)).append(" [label=<{<b>")
                    .append(escapeHtml(tableName)).append("</b>|");

            List<Column> columns = info.columns();
            for (int i = 0; i < columns.size(); i++) {
                Column col = columns.get(i);
                String colName = str(col.name());
                String typeName = str(col.typeName());
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

        sb.append("}\n");
        return sb.toString();
    }
}
