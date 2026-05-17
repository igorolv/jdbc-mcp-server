package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "IncomingForeignKey response payload.")
public record IncomingForeignKey(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "From Schema.", nullable = true)
        String fromSchema,
        @Schema(description = "From Table.", nullable = true)
        String fromTable,
        @Schema(description = "From Columns.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "To Columns.", nullable = true)
        List<String> toColumns
) {
}
