package ru.it_spectrum.ai.jdbc.mcp.model.query;

public record QueryParameter(
        String type,
        String name,
        String text,
        Integer index
) {
}
