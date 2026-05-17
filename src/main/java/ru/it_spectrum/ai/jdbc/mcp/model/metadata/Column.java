package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Column response payload.")
public record Column(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Ordinal Position.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int ordinalPosition,
        @Schema(description = "Type Name.", nullable = true)
        String typeName,
        @Schema(description = "Size.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int size,
        @Schema(description = "Decimal Digits.", nullable = true)
        Integer decimalDigits,
        @Schema(description = "Nullable.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean nullable,
        @Schema(description = "Default Value.", nullable = true)
        String defaultValue,
        @Schema(description = "Remarks.", nullable = true)
        String remarks,
        @Schema(description = "Auto Increment.", nullable = true)
        Boolean autoIncrement
) {
}
