package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Compact table ranking entry used for central and isolated table summaries.")
public record GraphTableSummary(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "Heuristic table role in the schema graph, such as central, isolated, lookup, or regular.", nullable = true)
        String classification,
        @Schema(description = "Number of relationship edges entering this table.", nullable = true)
        Integer incomingDegree,
        @Schema(description = "Number of relationship edges leaving this table.", nullable = true)
        Integer outgoingDegree,
        @Schema(description = "Total number of relationship edges connected to this table.", nullable = true)
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
