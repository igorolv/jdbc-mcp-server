package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class SchemaJoinPathService extends SchemaContextSupport {

    SchemaJoinPathService(MetadataService metadata, StatsService stats, SqlExecutor executor, SqlDialect dialect) {
        super(metadata, stats, executor, dialect);
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
            byFrom.computeIfAbsent(key(edge.fromSchema(), edge.fromTable()), ignored -> new ArrayList<>()).add(edge);
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
}
