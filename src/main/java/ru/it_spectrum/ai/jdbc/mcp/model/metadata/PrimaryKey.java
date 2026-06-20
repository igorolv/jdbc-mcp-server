package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Primary key constraint and ordered key columns for a table.")
public record PrimaryKey(
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> columns
) {
}
