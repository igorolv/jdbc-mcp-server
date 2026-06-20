package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Schema relationship graph metrics and edges for understanding table connectivity and join routes.")
public record SchemaGraph(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Number of tables inspected by the tool before caps were applied.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int tablesScanned,
        @Schema(description = "Number of table nodes in the schema graph.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int nodeCount,
        @Schema(description = "Total number of relationship edges in the graph response.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int edgeCount,
        @Schema(description = "Number of graph edges backed by declared foreign keys.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int declaredEdgeCount,
        @Schema(description = "Highest-degree tables that are likely hubs in the schema.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<GraphTableSummary> centralTables,
        @Schema(description = "Tables with no visible relationships in the scanned schema graph.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<GraphTableSummary> isolatedTables,
        @Schema(description = "Connected components discovered in the schema relationship graph.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<GraphComponent> connectedComponents,
        @Schema(description = "Possible relationship or lineage cycles found during traversal.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<CycleHint> cycles,
        @Schema(description = "Table nodes included in the schema graph response.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<GraphNode> nodes,
        @Schema(description = "Relationship edges included in the graph response.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<GraphEdgeSummary> edges,
        @Schema(description = "Shortest relationship path between the requested source and target tables, when requested.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        ShortestPath shortestPath
) {}
