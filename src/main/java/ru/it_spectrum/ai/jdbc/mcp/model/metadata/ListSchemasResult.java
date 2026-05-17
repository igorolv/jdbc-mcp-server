package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "List of schema names returned by listSchemas.")
public record ListSchemasResult(
        @Schema(description = "Schema names.", nullable = true)
        List<String> schemas
) {
}
