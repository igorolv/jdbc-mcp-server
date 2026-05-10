package ru.it_spectrum.ai.jdbc.mcp.model.snapshot;

import java.util.List;

public record SchemaSnapshot(
        boolean enabled,
        long ttlSeconds,
        int maxEntries,
        int describeCount,
        int listCount,
        long describeHits,
        long describeMisses,
        long listHits,
        long listMisses,
        String now,
        List<CachedSchemaEntry> schemas,
        List<ListCacheEntry> listEntries,
        String filterSchema
) {
}