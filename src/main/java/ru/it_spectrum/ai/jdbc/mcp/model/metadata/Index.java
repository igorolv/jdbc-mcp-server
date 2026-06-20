package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Index metadata for a table, with uniqueness and ordered column list.")
public record Index(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "True when the index or constraint enforces uniqueness.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean unique,
        @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> columns
) {
}
