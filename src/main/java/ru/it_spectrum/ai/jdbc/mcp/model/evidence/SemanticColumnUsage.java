package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record SemanticColumnUsage(
        String column,
        List<SemanticTermEvidence> outputLabels,
        List<SemanticTermEvidence> businessObjects
) {
    public SemanticColumnUsage {
        outputLabels = outputLabels == null ? List.of() : List.copyOf(outputLabels);
        businessObjects = businessObjects == null ? List.of() : List.copyOf(businessObjects);
    }
}
