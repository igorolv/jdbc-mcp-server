package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Cycle encountered while recursively expanding view or routine lineage.")
public record LineageCycle(
        @Schema(description = "Ordered object names or table identifiers forming the detected cycle.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> path
) {
}
