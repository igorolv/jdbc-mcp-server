package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

public record ObservedColumnUsage(
        String column,
        int queryCount,
        List<SemanticTermEvidence> contexts,
        List<QuerySourceRef> sourceRefs
) {
    public ObservedColumnUsage {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
