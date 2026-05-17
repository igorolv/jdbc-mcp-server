package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Foreign key from another table that references the described table.")
public record IncomingForeignKey(
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Schema of the source or left-side table in the relationship.", nullable = true)
        String fromSchema,
        @Schema(description = "Source or left-side table in the relationship.", nullable = true)
        String fromTable,
        @Schema(description = "Source-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "Target-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> toColumns
) {
}
