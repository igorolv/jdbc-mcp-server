package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

public record LineageObjectRef(
        String schema,
        String name,
        String type
) {
}
