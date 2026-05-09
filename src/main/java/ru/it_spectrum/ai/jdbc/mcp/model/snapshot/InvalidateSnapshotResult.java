package ru.it_spectrum.ai.jdbc.mcp.model.snapshot;

public record InvalidateSnapshotResult(
        String invalidated,
        String schema,
        String table
) {
}
