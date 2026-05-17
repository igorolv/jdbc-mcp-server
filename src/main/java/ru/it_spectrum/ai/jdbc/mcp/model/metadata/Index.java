package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Index response payload.")
public record Index(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Unique.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean unique,
        @Schema(description = "Columns.", nullable = true)
        List<String> columns
) {
}
