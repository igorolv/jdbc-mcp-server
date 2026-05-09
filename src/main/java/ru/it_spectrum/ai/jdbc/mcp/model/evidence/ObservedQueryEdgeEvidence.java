package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record ObservedQueryEdgeEvidence(
        int joinSupport,
        List<String> queryUids
) {
    public ObservedQueryEdgeEvidence {
        queryUids = queryUids == null ? List.of() : List.copyOf(queryUids);
    }
}
