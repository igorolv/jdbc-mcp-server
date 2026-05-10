package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record FindQueriesByTableResult(
        String schema,
        String table,
        List<Match> matches,
        int count
) {
    public record Match(
            String sourceKind,
            String sourcePath,
            String sourceUnit,
            String businessLabel,
            String businessDomain,
            String role,
            String alias,
            String rawName,
            String schemaResolved,
            String tableResolved
    ) {
    }
}