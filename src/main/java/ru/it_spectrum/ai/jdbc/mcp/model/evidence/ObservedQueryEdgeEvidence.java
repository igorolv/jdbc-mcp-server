package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

public record ObservedQueryEdgeEvidence(
        int joinSupport,
        List<QuerySourceRef> sourceRefs
) {
    public ObservedQueryEdgeEvidence {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
