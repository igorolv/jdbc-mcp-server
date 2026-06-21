package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "CHECK constraint: raw expression and, when parseable, the enumerable values it permits.")
public record CheckConstraint(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Columns referenced by the check expression.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> columns,
        @Schema(description = "SQL definition or constraint expression as reported by the database.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String definition,
        @Schema(description = "Enumerable values permitted by the check, parsed from the expression when possible.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> allowedValues
) {
}
