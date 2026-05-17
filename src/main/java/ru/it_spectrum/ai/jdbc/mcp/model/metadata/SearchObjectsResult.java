package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Cross-object search results returned by searchObjects.")
public record SearchObjectsResult(
        @Schema(description = "Matching database objects across tables, views, routines, sequences, and synonyms.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<SearchObjectEntry> objects
) {
}
