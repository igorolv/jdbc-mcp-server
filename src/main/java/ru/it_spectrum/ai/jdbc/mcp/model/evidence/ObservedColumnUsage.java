package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record ObservedColumnUsage(
        String column,
        int queryCount,
        List<SemanticTermEvidence> contexts,
        List<String> queryUids
) {
    public ObservedColumnUsage {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        queryUids = queryUids == null ? List.of() : List.copyOf(queryUids);
    }
}
