package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Runtime status of the usage catalog index and configured sources.")
public record UsageCatalogStatus(
        @Schema(description = "False when the usage catalog is disabled; true when indexing and lookups are allowed.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean catalogEnabled,
        @Schema(description = "Current usage-catalog index state, such as not_started, indexing, ready, or failed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String state,
        @Schema(description = "True while the usage catalog index is being built.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean indexing,
        @Schema(description = "Configured usage-catalog source paths and database-native sources considered for indexing.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> sources,
        @Schema(description = "Name of the connection this catalog belongs to.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String connection
) {

    /**
     * The status as the usage catalog itself knows it: the service is scoped to one connection but
     * does not know its name, so the tool layer stamps it in with {@link #withConnection(String)}.
     */
    public UsageCatalogStatus(boolean catalogEnabled, String state, boolean indexing, List<String> sources) {
        this(catalogEnabled, state, indexing, sources, null);
    }

    public static UsageCatalogStatus initial(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(catalogEnabled, "not_started", false, sources);
    }

    public UsageCatalogStatus withState(String state) {
        return new UsageCatalogStatus(catalogEnabled, state, indexing, sources, connection);
    }

    public UsageCatalogStatus withConnection(String connection) {
        return new UsageCatalogStatus(catalogEnabled, state, indexing, sources, connection);
    }

    public static UsageCatalogStatus indexing(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(catalogEnabled, "indexing", true, sources);
    }

    public static UsageCatalogStatus ready(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(catalogEnabled, "ready", false, sources);
    }
}
