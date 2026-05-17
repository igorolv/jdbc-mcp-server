package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "UniqueConstraint response payload.")
public record UniqueConstraint(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Columns.", nullable = true)
        List<String> columns
) {
}
