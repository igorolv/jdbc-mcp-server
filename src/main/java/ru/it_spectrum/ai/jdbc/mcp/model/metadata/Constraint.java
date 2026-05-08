package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import java.util.List;

public record Constraint(
        String name,
        String type,
        List<String> columns,
        String definition,
        String allowedValuesColumn,
        List<String> allowedValues,
        String referencedSchema,
        String referencedTable,
        List<String> referencedColumns
) {
}
