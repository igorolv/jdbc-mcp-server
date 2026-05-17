package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Connected component summary from the schema relationship graph.")
public record GraphComponent(
        @Schema(description = "Number of tables in the connected component or result group.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int size,
        @Schema(description = "Table identifiers belonging to this connected component.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> tables
) {
}
