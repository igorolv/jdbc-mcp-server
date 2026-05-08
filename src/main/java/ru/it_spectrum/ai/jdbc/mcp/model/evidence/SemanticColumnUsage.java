package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticColumnUsage(
        String column,
        List<SemanticTermEvidence> outputLabels,
        List<SemanticTermEvidence> businessObjects
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("column", column);
        out.put("outputLabels", outputLabels == null ? List.of() : outputLabels.stream()
                .map(SemanticTermEvidence::toMap)
                .toList());
        out.put("businessObjects", businessObjects == null ? List.of() : businessObjects.stream()
                .map(SemanticTermEvidence::toMap)
                .toList());
        return out;
    }
}
