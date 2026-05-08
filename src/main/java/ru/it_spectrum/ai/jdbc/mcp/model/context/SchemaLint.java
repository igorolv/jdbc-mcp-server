package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record SchemaLint(
        String schema,
        String table,
        Set<String> checks,
        int tablesScanned,
        int findingCount,
        boolean truncated,
        List<Map<String, Object>> findings
) {}
