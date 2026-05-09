package ru.it_spectrum.ai.jdbc.mcp.model.context;

import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;

import java.util.List;
import java.util.Map;

public record QueryContextTable(
        String schema,
        String name,
        String type,
        String classification,
        String remarks,
        PrimaryKey primaryKey,
        Map<String, List<String>> allowedValues,
        List<QueryContextColumn> relevantColumns,
        List<QueryContextConstraint> constraints,
        List<ForeignKey> foreignKeys,
        List<CompactTable.CompactIndex> indexes,
        SemanticTableCandidate semanticMatch,
        QueryContextSample sample
) {
}
