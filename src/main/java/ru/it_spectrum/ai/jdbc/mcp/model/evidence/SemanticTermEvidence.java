package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SemanticTermEvidence(
        String value,
        int support,
        List<String> queryUids
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", value);
        out.put("support", support);
        out.put("queryUids", queryUids == null ? List.of() : queryUids);
        return out;
    }
}
