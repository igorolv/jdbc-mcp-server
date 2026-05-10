package ru.it_spectrum.ai.jdbc.mcp.model.usage;

public record InvalidateUsageCatalogCacheResult(
        int recordsLoaded,
        int parseFailed,
        int paramsStored,
        int tablesExtracted,
        int columnsExtracted,
        int joinPairsExtracted,
        int outputsStored,
        int fieldUsagesStored,
        long indexBuildMs
) {
}