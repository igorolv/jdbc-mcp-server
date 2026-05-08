package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

public record QueryContext(
        String schema,
        String terms,
        List<String> requestedTables,
        boolean includeSamples,
        int tableCount,
        List<Map<String, Object>> semanticMatches,
        List<Map<String, Object>> tables,
        List<Map<String, Object>> relationships,
        List<Map<String, Object>> joinPaths
) {}
