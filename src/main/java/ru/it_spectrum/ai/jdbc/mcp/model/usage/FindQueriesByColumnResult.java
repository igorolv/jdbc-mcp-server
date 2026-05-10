package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record FindQueriesByColumnResult(
        String schema,
        String table,
        String column,
        List<Match> matches,
        int count
) {
    public record Match(
            String sourceKind,
            String sourcePath,
            String sourceUnit,
            String businessLabel,
            String businessDomain,
            String context,
            String schemaResolved,
            String tableResolved,
            String columnName
    ) {
    }
}