package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Database column metadata, including type, nullability, defaults, comments, and auto-increment status.")
public record Column(
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "One-based ordinal position of the column within the table.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int ordinalPosition,
        @Schema(description = "Database-specific type name reported by JDBC metadata.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String typeName,
        @Schema(description = "Number of tables in the connected component or result group.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int size,
        @Schema(description = "Scale for numeric columns, null when not applicable or not reported.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer decimalDigits,
        @Schema(description = "True when the column accepts NULL values.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean nullable,
        @Schema(description = "Default value expression for the column or documented parameter.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String defaultValue,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String remarks,
        @Schema(description = "True when the database reports the column as auto-incrementing or identity-like.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean autoIncrement
) {
}
