package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record SemanticTableCandidate(
        String schema,
        String table,
        int support,
        List<SemanticTermEvidence> matchedTerms,
        List<String> queryUids
) {
    public SemanticTableCandidate {
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        queryUids = queryUids == null ? List.of() : List.copyOf(queryUids);
    }
}
