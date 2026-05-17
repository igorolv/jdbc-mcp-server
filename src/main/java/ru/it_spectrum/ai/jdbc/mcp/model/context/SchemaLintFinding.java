package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Single schema lint finding with severity, location, explanation, and recommendation.")
public record SchemaLintFinding(
        @Schema(description = "Finding severity used to prioritize follow-up work.", nullable = true)
        String severity,
        @Schema(description = "Identifier of the lint check that produced the finding.", nullable = true)
        String check,
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Table name within the schema.", nullable = true)
        String table,
        @Schema(description = "Column name within the table.", nullable = true)
        String column,
        @Schema(description = "Human-readable diagnostic message.", nullable = true)
        String message,
        @Schema(description = "Suggested action an agent or user can consider for this finding.", nullable = true)
        String recommendation
) {
}
