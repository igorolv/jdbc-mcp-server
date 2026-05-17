package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "Table candidate selected by semantic search over usage-catalog terms and source references.")
public record SemanticTableCandidate(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Number of source records or terms supporting this semantic candidate.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int support,
        @Schema(description = "Search terms or semantic terms that matched this candidate.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<SemanticTermEvidence> matchedTerms,
        @Schema(description = "Usage-catalog source records that support this evidence item.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QuerySourceRef> sourceRefs
) {
    public SemanticTableCandidate {
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
