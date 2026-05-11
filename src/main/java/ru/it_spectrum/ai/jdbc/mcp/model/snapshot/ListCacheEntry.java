package ru.it_spectrum.ai.jdbc.mcp.model.snapshot;

public record ListCacheEntry(
        String schema,
        String namePattern,
        String types,
        int rows
) {
}
