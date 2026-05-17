package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryContextColumn response payload.")
public record QueryContextColumn(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Nullable.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean nullable,
        @Schema(description = "Primary Key.", nullable = true)
        Boolean primaryKey,
        @Schema(description = "Foreign Key.", nullable = true)
        Boolean foreignKey,
        @Schema(description = "Allowed Values.", nullable = true)
        List<String> allowedValues
) {
}
