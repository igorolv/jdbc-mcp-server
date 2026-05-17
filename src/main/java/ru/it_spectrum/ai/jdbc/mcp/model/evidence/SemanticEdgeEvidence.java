package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "Semantic evidence that two tables are related because known queries use them in shared business contexts.")
public record SemanticEdgeEvidence(
        @Schema(description = "Business domains shared by known queries that use both sides of the relationship.", nullable = true)
        List<SemanticTermEvidence> sharedBusinessDomains,
        @Schema(description = "Business objects shared by known queries that use both sides of the relationship.", nullable = true)
        List<SemanticTermEvidence> sharedBusinessObjects,
        @Schema(description = "Output labels shared by known queries that use both sides of the relationship.", nullable = true)
        List<SemanticTermEvidence> sharedOutputLabels,
        @Schema(description = "Number of known queries where both tables occur together.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int coOccurringQueryCount,
        @Schema(description = "Catalog source references for queries where both tables occur together.", nullable = true)
        List<QuerySourceRef> coOccurringSourceRefs
) {
    public SemanticEdgeEvidence {
        sharedBusinessDomains = sharedBusinessDomains == null ? List.of() : List.copyOf(sharedBusinessDomains);
        sharedBusinessObjects = sharedBusinessObjects == null ? List.of() : List.copyOf(sharedBusinessObjects);
        sharedOutputLabels = sharedOutputLabels == null ? List.of() : List.copyOf(sharedOutputLabels);
        coOccurringSourceRefs = coOccurringSourceRefs == null ? List.of() : List.copyOf(coOccurringSourceRefs);
    }

    public boolean isEmpty() {
        return coOccurringQueryCount == 0
                && sharedBusinessDomains.isEmpty()
                && sharedBusinessObjects.isEmpty()
                && sharedOutputLabels.isEmpty();
    }
}
