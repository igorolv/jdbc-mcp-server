package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record SemanticTermEvidence(
        String value,
        int support,
        List<String> queryUids
) {
    public SemanticTermEvidence {
        queryUids = queryUids == null ? List.of() : List.copyOf(queryUids);
    }
}
