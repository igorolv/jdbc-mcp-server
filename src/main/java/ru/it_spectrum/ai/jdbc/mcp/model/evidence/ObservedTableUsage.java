package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import java.util.List;

public record ObservedTableUsage(
        int queryCount,
        List<String> queryUids,
        List<ObservedColumnUsage> columns
) {
    public ObservedTableUsage {
        queryUids = queryUids == null ? List.of() : List.copyOf(queryUids);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
