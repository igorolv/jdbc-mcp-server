package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Validation result for a SQL statement, including guard, parameter, driver, and inspection diagnostics.")
public record QueryValidationResult(
        @Schema(description = "True when the statement passed guard, parameter, and driver validation.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean valid,
        @Schema(description = "Number of SQL parameters expected or validated.", nullable = true)
        Integer parameters,
        @Schema(description = "Number of result columns reported by driver validation when available.", nullable = true)
        Integer columns,
        @Schema(description = "Validation stage that failed, such as guard, params, or driver.", nullable = true)
        String stage,
        @Schema(description = "Human-readable error message explaining why the requested operation failed.", nullable = true)
        String error,
        @Schema(description = "Parsed query inspection that underpins validation, lint, or lineage results.", nullable = true)
        QueryInspection inspection
) {
    public static QueryValidationResult valid(int parameters, int columns, QueryInspection inspection) {
        return new QueryValidationResult(true, parameters, columns, null, null, inspection);
    }

    public static QueryValidationResult invalid(String stage, String error, QueryInspection inspection) {
        return new QueryValidationResult(false, null, null, stage, error, inspection);
    }
}
