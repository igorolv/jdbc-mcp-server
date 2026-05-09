package ru.it_spectrum.ai.jdbc.mcp.model.context;

public record ErrorTable(
        String schema,
        String name,
        String type,
        String remarks,
        String error
) implements ContextTable {
}
