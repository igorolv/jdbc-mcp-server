package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Constraint response payload.")
public record Constraint(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Columns.", nullable = true)
        List<String> columns,
        @Schema(description = "Definition.", nullable = true)
        String definition,
        @Schema(description = "Allowed Values Column.", nullable = true)
        String allowedValuesColumn,
        @Schema(description = "Allowed Values.", nullable = true)
        List<String> allowedValues,
        @Schema(description = "Referenced Schema.", nullable = true)
        String referencedSchema,
        @Schema(description = "Referenced Table.", nullable = true)
        String referencedTable,
        @Schema(description = "Referenced Columns.", nullable = true)
        List<String> referencedColumns
) {
}
