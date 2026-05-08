package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

public record SchemaGraph(
        String schema,
        int tablesScanned,
        int nodeCount,
        int edgeCount,
        int declaredEdgeCount,
        List<Map<String, Object>> centralTables,
        List<Map<String, Object>> isolatedTables,
        List<Map<String, Object>> connectedComponents,
        List<Map<String, Object>> cycles,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges,
        Map<String, Object> shortestPath
) {}
