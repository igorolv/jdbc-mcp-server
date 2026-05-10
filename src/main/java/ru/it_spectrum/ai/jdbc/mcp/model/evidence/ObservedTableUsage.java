package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

public record ObservedTableUsage(
        int queryCount,
        List<QuerySourceRef> sourceRefs,
        List<ObservedColumnUsage> columns
) {
    public ObservedTableUsage {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
