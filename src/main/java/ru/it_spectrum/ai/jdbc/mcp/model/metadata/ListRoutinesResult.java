package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "List of routines returned by listRoutines.")
public record ListRoutinesResult(
        @Schema(description = "Function, procedure, and package entries.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<RoutineEntry> routines
) {
}
