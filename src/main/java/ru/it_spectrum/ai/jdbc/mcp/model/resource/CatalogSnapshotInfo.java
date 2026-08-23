package ru.it_spectrum.ai.jdbc.mcp.model.resource;

import java.util.List;

/**
 * Version and coverage metadata for the persistent structure snapshot.
 */
public record CatalogSnapshotInfo(
        int formatVersion,
        long snapshotVersion,
        String builtAt,
        List<String> coveredSchemas
) {
    public CatalogSnapshotInfo {
        coveredSchemas = coveredSchemas == null ? List.of() : List.copyOf(coveredSchemas);
    }
}
