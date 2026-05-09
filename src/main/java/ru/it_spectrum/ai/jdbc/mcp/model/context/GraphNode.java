package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record GraphNode(
        String id,
        String schema,
        String table,
        String classification,
        int incomingDegree,
        int outgoingDegree,
        int totalDegree,
        int columnCount,
        List<String> primaryKey
) {
}
