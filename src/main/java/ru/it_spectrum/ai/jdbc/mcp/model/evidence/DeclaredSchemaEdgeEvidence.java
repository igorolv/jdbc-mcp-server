package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record DeclaredSchemaEdgeEvidence(
        String foreignKeyName,
        List<String> fromColumns,
        List<String> toColumns
) {
    public DeclaredSchemaEdgeEvidence {
        fromColumns = fromColumns == null ? List.of() : List.copyOf(fromColumns);
        toColumns = toColumns == null ? List.of() : List.copyOf(toColumns);
    }
}
