package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Runtime status of the usage catalog index and configured sources.")
public record UsageCatalogStatus(
        @Schema(description = "False when the usage catalog is disabled; true when indexing and lookups are allowed.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean catalogEnabled,
        @Schema(description = "Current usage-catalog index state, such as not_started, indexing, ready, or failed.", nullable = true)
        String state,
        @Schema(description = "True while the usage catalog index is being built.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean indexing,
        @Schema(description = "Configured usage-catalog source paths and database-native sources considered for indexing.", nullable = true)
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
