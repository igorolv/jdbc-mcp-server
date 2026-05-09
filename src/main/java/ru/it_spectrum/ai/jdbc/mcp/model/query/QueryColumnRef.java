package ru.it_spectrum.ai.jdbc.mcp.model.query;

public record QueryColumnRef(
        String name,
        String qualifier,
        String text,
        String context
) {
}
