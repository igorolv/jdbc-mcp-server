package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record UsageCatalogStatus(
        boolean catalogEnabled,
        String state,
        boolean indexing,
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
