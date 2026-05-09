package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record SchemaOverview(
        String schema,
        String namePattern,
        boolean includeViews,
        boolean includeStats,
        boolean includeObserved,
        int tableCount,
        int returnedTableCount,
        boolean truncated,
        List<ContextTable> tables,
        List<RelationshipEdge> relationships
) {}
