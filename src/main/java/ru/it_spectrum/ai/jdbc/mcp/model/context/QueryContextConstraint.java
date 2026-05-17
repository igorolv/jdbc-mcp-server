package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Constraint metadata included in query authoring context, including extracted allowed values when available.")
public record QueryContextConstraint(
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "SQL definition or constraint expression as reported by the database.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String definition,
        @Schema(description = "Column whose allowed values were parsed from a CHECK constraint.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String allowedValuesColumn,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> allowedValues
) {
}
