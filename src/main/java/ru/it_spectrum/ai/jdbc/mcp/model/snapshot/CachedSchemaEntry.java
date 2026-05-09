package ru.it_spectrum.ai.jdbc.mcp.model.snapshot;

import java.util.List;

public record CachedSchemaEntry(
        String schema,
        int tableCount,
        List<CachedTableEntry> tables
) {
}
