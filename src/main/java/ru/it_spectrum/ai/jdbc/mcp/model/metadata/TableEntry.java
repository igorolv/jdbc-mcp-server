package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "TableEntry response payload.")
public record TableEntry(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Remarks.", nullable = true)
        String remarks
) {
    public TableEntry(String schema, String name, String type) {
        this(schema, name, type, null);
    }
}
