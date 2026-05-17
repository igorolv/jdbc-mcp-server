package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "PrimaryKey response payload.")
public record PrimaryKey(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Columns.", nullable = true)
        List<String> columns
) {
}
