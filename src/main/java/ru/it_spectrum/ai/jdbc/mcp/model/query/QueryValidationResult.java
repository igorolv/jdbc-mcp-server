package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.Opaque;

@Schema(description = "Validation result for a SQL statement, including guard, parameter, driver, and inspection diagnostics.")
public record QueryValidationResult(
        @Schema(description = "True when the statement passed guard, parameter, and driver validation.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean valid,
        @Schema(description = "Number of SQL parameters expected or validated.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer parameters,
        @Schema(description = "Number of result columns reported by driver validation when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer columns,
        @Schema(description = "Validation stage that failed, such as guard, params, or driver.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String stage,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error,
        @Schema(description = "Parsed query inspection that underpins validation (opaque; the inspectQuery tool returns the typed form).", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Opaque<QueryInspection> inspection
) {
    public static QueryValidationResult valid(int parameters, int columns, QueryInspection inspection) {
        return new QueryValidationResult(true, parameters, columns, null, null, Opaque.of(inspection));
    }

    public static QueryValidationResult invalid(String stage, String error, QueryInspection inspection) {
        return new QueryValidationResult(false, null, null, stage, error, Opaque.of(inspection));
    }
}
