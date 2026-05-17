package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "SchemaLintFinding response payload.")
public record SchemaLintFinding(
        @Schema(description = "Severity.", nullable = true)
        String severity,
        @Schema(description = "Check.", nullable = true)
        String check,
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Column.", nullable = true)
        String column,
        @Schema(description = "Message.", nullable = true)
        String message,
        @Schema(description = "Recommendation.", nullable = true)
        String recommendation
) {
}
