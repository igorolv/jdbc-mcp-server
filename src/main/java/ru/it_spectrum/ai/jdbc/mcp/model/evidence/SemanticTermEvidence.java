package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

public record SemanticTermEvidence(
        String value,
        int support,
        List<QuerySourceRef> sourceRefs
) {
    public SemanticTermEvidence {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
