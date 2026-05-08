package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

public record FindJoinPaths(
        String fromSchema,
        String fromTable,
        String toSchema,
        String toTable,
        int maxDepth,
        boolean includeObserved,
        int schemaTablesScanned,
        int pathCount,
        List<List<Map<String, Object>>> paths
) {}
