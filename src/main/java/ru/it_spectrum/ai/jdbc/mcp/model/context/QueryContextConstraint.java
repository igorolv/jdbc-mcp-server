package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Constraint metadata included in query authoring context, including extracted allowed values when available.")
public record QueryContextConstraint(
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true)
        String type,
        @Schema(description = "SQL definition or constraint expression as reported by the database.", nullable = true)
        String definition,
        @Schema(description = "Column whose allowed values were parsed from a CHECK constraint.", nullable = true)
        String allowedValuesColumn,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true)
        List<String> allowedValues
) {
}
