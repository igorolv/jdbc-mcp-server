package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Possible cycle detected in the schema relationship graph.")
public record CycleHint(
        @Schema(description = "Ordered tables that form the possible cycle.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> tables,
        @Schema(description = "Additional context about support, limits, interpretation, or engine-specific behavior.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String note
) {
}
