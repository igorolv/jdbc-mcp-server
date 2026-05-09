package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record SchemaGraph(
        String schema,
        int tablesScanned,
        int nodeCount,
        int edgeCount,
        int declaredEdgeCount,
        List<GraphTableSummary> centralTables,
        List<GraphTableSummary> isolatedTables,
        List<GraphComponent> connectedComponents,
        List<CycleHint> cycles,
        List<GraphNode> nodes,
        List<GraphEdgeSummary> edges,
        ShortestPath shortestPath
) {}
