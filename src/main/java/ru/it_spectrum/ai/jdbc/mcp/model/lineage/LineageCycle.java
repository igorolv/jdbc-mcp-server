package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "LineageCycle response payload.")
public record LineageCycle(
        @Schema(description = "Path.", nullable = true)
        List<String> path
) {
}
