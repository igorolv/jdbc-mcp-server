package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record QueryContextConstraint(
        String name,
        String type,
        String definition,
        String allowedValuesColumn,
        List<String> allowedValues
) {
}
