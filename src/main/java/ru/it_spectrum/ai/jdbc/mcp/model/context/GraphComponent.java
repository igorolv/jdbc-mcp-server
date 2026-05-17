package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "GraphComponent response payload.")
public record GraphComponent(
        @Schema(description = "Size.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int size,
        @Schema(description = "Tables.", nullable = true)
        List<String> tables
) {
}
