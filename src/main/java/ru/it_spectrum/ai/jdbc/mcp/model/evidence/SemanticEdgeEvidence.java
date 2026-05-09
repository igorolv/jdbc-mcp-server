package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record SemanticEdgeEvidence(
        List<SemanticTermEvidence> sharedBusinessDomains,
        List<SemanticTermEvidence> sharedBusinessObjects,
        List<SemanticTermEvidence> sharedOutputLabels,
        int coOccurringQueryCount,
        List<String> coOccurringQueryUids
) {
    public SemanticEdgeEvidence {
        sharedBusinessDomains = sharedBusinessDomains == null ? List.of() : List.copyOf(sharedBusinessDomains);
        sharedBusinessObjects = sharedBusinessObjects == null ? List.of() : List.copyOf(sharedBusinessObjects);
        sharedOutputLabels = sharedOutputLabels == null ? List.of() : List.copyOf(sharedOutputLabels);
        coOccurringQueryUids = coOccurringQueryUids == null ? List.of() : List.copyOf(coOccurringQueryUids);
    }

    public boolean isEmpty() {
        return coOccurringQueryCount == 0
                && sharedBusinessDomains.isEmpty()
                && sharedBusinessObjects.isEmpty()
                && sharedOutputLabels.isEmpty();
    }
}
