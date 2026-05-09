package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

public record Column(
        String name,
        int ordinalPosition,
        String typeName,
        int size,
        Integer decimalDigits,
        boolean nullable,
        String defaultValue,
        String remarks,
        Boolean autoIncrement
) {
}
