package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Single schema lint finding with severity, location, explanation, and recommendation.")
public record SchemaLintFinding(
        @Schema(description = "Finding severity used to prioritize follow-up work.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String severity,
        @Schema(description = "Identifier of the lint check that produced the finding.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String check,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String table,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String column,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String message,
        @Schema(description = "Suggested action an agent or user can consider for this finding.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String recommendation
) {
}
