package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "SemanticTableCandidate response payload.")
public record SemanticTableCandidate(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Support.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int support,
        @Schema(description = "Matched Terms.", nullable = true)
        List<SemanticTermEvidence> matchedTerms,
        @Schema(description = "Source Refs.", nullable = true)
        List<QuerySourceRef> sourceRefs
) {
    public SemanticTableCandidate {
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
