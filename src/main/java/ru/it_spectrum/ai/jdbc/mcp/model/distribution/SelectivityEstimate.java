package ru.it_spectrum.ai.jdbc.mcp.model.distribution;

import ru.it_spectrum.ai.jdbc.mcp.model.JsonKey;

public record SelectivityEstimate(
        String schema,
        String table,
        String predicate,
        @JsonKey("estimated_rows") Long estimatedRows,
        @JsonKey("baseline_rows") Long baselineRows,
        Double selectivity,
        String note
) {}
