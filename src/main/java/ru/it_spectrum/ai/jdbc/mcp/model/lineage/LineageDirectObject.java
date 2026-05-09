package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

public record LineageDirectObject(
        String schema,
        String name,
        String type,
        String alias,
        String source,
        String resolutionStatus
) {
}
