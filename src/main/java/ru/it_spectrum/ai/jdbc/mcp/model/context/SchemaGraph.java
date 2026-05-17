package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "SchemaGraph response payload.")
public record SchemaGraph(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Tables Scanned.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tablesScanned,
        @Schema(description = "Node Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int nodeCount,
        @Schema(description = "Edge Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int edgeCount,
        @Schema(description = "Declared Edge Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int declaredEdgeCount,
        @Schema(description = "Central Tables.", nullable = true)
        List<GraphTableSummary> centralTables,
        @Schema(description = "Isolated Tables.", nullable = true)
        List<GraphTableSummary> isolatedTables,
        @Schema(description = "Connected Components.", nullable = true)
        List<GraphComponent> connectedComponents,
        @Schema(description = "Cycles.", nullable = true)
        List<CycleHint> cycles,
        @Schema(description = "Nodes.", nullable = true)
        List<GraphNode> nodes,
        @Schema(description = "Edges.", nullable = true)
        List<GraphEdgeSummary> edges,
        @Schema(description = "Shortest Path.", nullable = true)
        ShortestPath shortestPath
) {}
