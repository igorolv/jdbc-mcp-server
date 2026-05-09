package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record TableContext(
        String rootSchema,
        String rootTable,
        int depth,
        boolean includeIncoming,
        boolean includeStats,
        boolean includeObserved,
        List<ContextTable> tables,
        List<RelationshipEdge> relationships
) {}
