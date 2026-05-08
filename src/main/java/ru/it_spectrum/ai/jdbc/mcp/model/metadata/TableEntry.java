package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

public record TableEntry(
        String schema,
        String name,
        String type,
        String remarks
) {
    public TableEntry(String schema, String name, String type) {
        this(schema, name, type, null);
    }
}
