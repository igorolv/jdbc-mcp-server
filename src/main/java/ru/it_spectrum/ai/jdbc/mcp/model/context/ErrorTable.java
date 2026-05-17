package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "ErrorTable response payload.")
public record ErrorTable(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Remarks.", nullable = true)
        String remarks,
        @Schema(description = "Error.", nullable = true)
        String error
) implements ContextTable {
}
