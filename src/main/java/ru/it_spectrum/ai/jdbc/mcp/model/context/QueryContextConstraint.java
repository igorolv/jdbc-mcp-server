package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryContextConstraint response payload.")
public record QueryContextConstraint(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Definition.", nullable = true)
        String definition,
        @Schema(description = "Allowed Values Column.", nullable = true)
        String allowedValuesColumn,
        @Schema(description = "Allowed Values.", nullable = true)
        List<String> allowedValues
) {
}
