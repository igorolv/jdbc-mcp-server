package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Foreign key from another table that references the described table.")
public record IncomingForeignKey(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromSchema,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String fromTable,
        @Schema(description = "Source-side columns participating in the relationship, in join/key order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> fromColumns,
        @Schema(description = "Target-side columns participating in the relationship, in join/key order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> toColumns
) {
}
