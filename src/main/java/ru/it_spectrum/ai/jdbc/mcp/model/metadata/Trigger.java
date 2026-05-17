package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Trigger response payload.")
public record Trigger(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Table.", nullable = true)
        String table,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Timing.", nullable = true)
        String timing,
        @Schema(description = "Events.", nullable = true)
        List<String> events,
        @Schema(description = "Enabled.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean enabled,
        @Schema(description = "Definition.", nullable = true)
        String definition
) {
}
