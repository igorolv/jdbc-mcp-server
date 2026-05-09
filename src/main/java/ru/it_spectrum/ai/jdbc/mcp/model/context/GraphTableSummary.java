package ru.it_spectrum.ai.jdbc.mcp.model.context;

public record GraphTableSummary(
        String schema,
        String table,
        String classification,
        Integer incomingDegree,
        Integer outgoingDegree,
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
