package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Single schema lint finding with severity, location, explanation, and recommendation.")
public record SchemaLintFinding(
        @Schema(description = "Finding severity used to prioritize follow-up work.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String severity,
        @Schema(description = "Identifier of the lint check that produced the finding.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String check,
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(description = "Column name within the table.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String column,
        @Schema(description = "Human-readable diagnostic message.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String message,
        @Schema(description = "Suggested action an agent or user can consider for this finding.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String recommendation
) {
}
