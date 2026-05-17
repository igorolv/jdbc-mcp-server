package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "GraphTableSummary response payload.")
public record GraphTableSummary(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Classification.", nullable = true)
        String classification,
        @Schema(description = "Incoming Degree.", nullable = true)
        Integer incomingDegree,
        @Schema(description = "Outgoing Degree.", nullable = true)
        Integer outgoingDegree,
        @Schema(description = "Total Degree.", nullable = true)
        Integer totalDegree
) {
    public static GraphTableSummary central(GraphNode node) {
        return new GraphTableSummary(
                node.schema(), node.table(), node.classification(),
                node.incomingDegree(), node.outgoingDegree(), node.totalDegree());
    }

    public static GraphTableSummary isolated(GraphNode node) {
        return new GraphTableSummary(
                node.schema(), node.table(), node.classification(),
                null, null, null);
    }
}
