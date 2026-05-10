package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

public record RoutineEntry(
        String schema,
        String name,
        String type
) {
}