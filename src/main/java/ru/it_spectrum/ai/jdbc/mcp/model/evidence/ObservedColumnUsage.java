package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ObservedColumnUsage(
        String column,
        int queryCount,
        List<SemanticTermEvidence> contexts,
        List<String> queryUids
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("column", column);
        out.put("queryCount", queryCount);
        out.put("contexts", contexts == null ? List.of() : contexts.stream()
                .map(SemanticTermEvidence::toMap)
                .toList());
        out.put("queryUids", queryUids == null ? List.of() : queryUids);
        return out;
    }
}
