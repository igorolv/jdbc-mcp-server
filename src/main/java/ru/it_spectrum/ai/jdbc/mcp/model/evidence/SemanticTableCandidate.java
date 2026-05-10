package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

public record SemanticTableCandidate(
        String schema,
        String table,
        int support,
        List<SemanticTermEvidence> matchedTerms,
        List<QuerySourceRef> sourceRefs
) {
    public SemanticTableCandidate {
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
