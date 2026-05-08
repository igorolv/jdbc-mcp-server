package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

public record SchemaOverview(
        String schema,
        String namePattern,
        boolean includeViews,
        boolean includeStats,
        boolean includeObserved,
        int tableCount,
        int returnedTableCount,
        boolean truncated,
        List<Map<String, Object>> tables,
        List<Map<String, Object>> relationships
) {}
