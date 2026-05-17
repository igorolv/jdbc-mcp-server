package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "QueryValidationResult response payload.")
public record QueryValidationResult(
        @Schema(description = "Valid.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean valid,
        @Schema(description = "Parameters.", nullable = true)
        Integer parameters,
        @Schema(description = "Columns.", nullable = true)
        Integer columns,
        @Schema(description = "Stage.", nullable = true)
        String stage,
        @Schema(description = "Error.", nullable = true)
        String error,
        @Schema(description = "Inspection.", nullable = true)
        QueryInspection inspection
) {
    public static QueryValidationResult valid(int parameters, int columns, QueryInspection inspection) {
        return new QueryValidationResult(true, parameters, columns, null, null, inspection);
    }

    public static QueryValidationResult invalid(String stage, String error, QueryInspection inspection) {
        return new QueryValidationResult(false, null, null, stage, error, inspection);
    }
}
