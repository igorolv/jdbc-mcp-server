package ru.it_spectrum.ai.jdbc.mcp.model.snapshot;

import java.util.List;

public record SchemaSnapshot(
        boolean enabled,
        long ttlSeconds,
        int maxEntries,
        List<CachedSchemaEntry> schemas,
        List<ListCacheEntry> listEntries,
        String filterSchema
) {
}