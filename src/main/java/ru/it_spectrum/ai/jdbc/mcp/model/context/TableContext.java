package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;

public record TableContext(
        String rootSchema,
        String rootTable,
        int depth,
        boolean includeIncoming,
        boolean includeStats,
        boolean includeObserved,
        List<Map<String, Object>> tables,
        List<Map<String, Object>> relationships
) {}
