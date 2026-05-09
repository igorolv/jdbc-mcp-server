package ru.it_spectrum.ai.jdbc.mcp.model.query;

public record QueryTableRef(
        String schema,
        String name,
        String fullName,
        String alias,
        String source
) {
}
