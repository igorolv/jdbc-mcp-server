package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record GraphEdgeSummary(
        String relationshipType,
        String name,
        String from,
        String to,
        String fromTable,
        List<String> fromColumns,
        String toTable,
        List<String> toColumns
) {
}
