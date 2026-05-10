package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

public record SearchObjectEntry(
        String schema,
        String name,
        String type
) {
}