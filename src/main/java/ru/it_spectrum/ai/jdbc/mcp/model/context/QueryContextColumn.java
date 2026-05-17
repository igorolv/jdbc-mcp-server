package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Column metadata included in query authoring context.")
public record QueryContextColumn(
        @Schema(description = "Column name as reported by database metadata.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Database type name for the column.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "True when the column accepts NULL values.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean nullable,
        @Schema(description = "True when the column participates in the table primary key.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean primaryKey,
        @Schema(description = "True when the column participates in an outgoing foreign key.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean foreignKey,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> allowedValues
) {
}
