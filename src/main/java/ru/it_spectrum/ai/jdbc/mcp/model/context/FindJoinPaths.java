package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record FindJoinPaths(
        String fromSchema,
        String fromTable,
        String toSchema,
        String toTable,
        int maxDepth,
        boolean includeObserved,
        int schemaTablesScanned,
        int pathCount,
        List<List<JoinPathStep>> paths
) {}
