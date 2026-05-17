package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "UsageCatalogStatus response payload.")
public record UsageCatalogStatus(
        @Schema(description = "Catalog Enabled.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean catalogEnabled,
        @Schema(description = "State.", nullable = true)
        String state,
        @Schema(description = "Indexing.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean indexing,
        @Schema(description = "Sources.", nullable = true)
        List<String> sources
) {

    public static UsageCatalogStatus initial(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(catalogEnabled, "not_started", false, sources);
    }

    public UsageCatalogStatus withState(String state) {
        return new UsageCatalogStatus(catalogEnabled, state, indexing, sources);
    }

    public static UsageCatalogStatus indexing(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(catalogEnabled, "indexing", true, sources);
    }

    public static UsageCatalogStatus ready(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(catalogEnabled, "ready", false, sources);
    }
}
