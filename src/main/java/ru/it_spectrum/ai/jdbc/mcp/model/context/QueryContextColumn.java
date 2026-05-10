package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record QueryContextColumn(
        String name,
        String type,
        boolean nullable,
        Boolean primaryKey,
        Boolean foreignKey,
        List<String> allowedValues
) {
}
