package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ObservedTableUsage(
        int queryCount,
        List<String> queryUids,
        List<ObservedColumnUsage> columns
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queryCount", queryCount);
        out.put("queryUids", queryUids == null ? List.of() : queryUids);
        out.put("columns", columns == null ? List.of() : columns.stream()
                .map(ObservedColumnUsage::toMap)
                .toList());
        return out;
    }
}
