package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Non-fatal lineage warning that explains partial or best-effort expansion.")
public record LineageWarning(
        @Schema(description = "Stable warning or diagnostic code.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String code,
        @Schema(description = "Human-readable diagnostic message.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String message
) {
}
