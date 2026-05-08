package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ObservedQueryEdgeEvidence(
        int joinSupport,
        List<String> queryUids
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("joinSupport", joinSupport);
        out.put("queryUids", queryUids == null ? List.of() : queryUids);
        return out;
    }
}
