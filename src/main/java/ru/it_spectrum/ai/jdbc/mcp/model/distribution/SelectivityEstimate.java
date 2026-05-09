package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

public record SelectivityEstimate(
        String schema,
        String table,
        String predicate,
        Long estimatedRows,
        Long baselineRows,
        Double selectivity,
        String note
) {}
