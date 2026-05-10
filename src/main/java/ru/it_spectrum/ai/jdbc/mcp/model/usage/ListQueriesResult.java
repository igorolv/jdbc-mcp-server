package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record ListQueriesResult(
        List<QueryEntry> queries,
        int limit,
        int offset,
        int count
) {
    public record QueryEntry(
            String sourceKind,
            String sourcePath,
            String sourceUnit,
            String businessLabel,
            String businessDomain,
            String parseStatus,
            String ingestedAt
    ) {
    }
}