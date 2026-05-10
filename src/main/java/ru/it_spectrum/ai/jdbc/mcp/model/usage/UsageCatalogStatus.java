package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.time.Instant;
import java.util.List;

public record UsageCatalogStatus(
        boolean catalogEnabled,
        String state,
        boolean indexing,
        List<String> sources,
        String startedAt,
        Integer filesScanned,
        Integer recordsLoaded,
        Integer invalidFiles,
        Integer duplicateUids,
        List<String> errors,
        List<String> duplicates,
        String lastSuccessfulBuildAt,
        String finishedAt,
        Long totalBuildMs,
        Integer parseFailed,
        Integer paramsStored,
        Integer tablesExtracted,
        Integer columnsExtracted,
        Integer joinPairsExtracted,
        Integer outputsStored,
        Integer fieldUsagesStored,
        Long indexBuildMs,
        Integer tablesResolved,
        Integer tablesAmbiguous,
        Integer tablesUnresolved
) {

    public static UsageCatalogStatus initial(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(
                catalogEnabled, "not_started", false, sources, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null
        );
    }

    public UsageCatalogStatus withState(String state) {
        return new UsageCatalogStatus(
                catalogEnabled, state, indexing, sources, startedAt, filesScanned, recordsLoaded,
                invalidFiles, duplicateUids, errors, duplicates, lastSuccessfulBuildAt, finishedAt,
                totalBuildMs, parseFailed, paramsStored, tablesExtracted, columnsExtracted,
                joinPairsExtracted, outputsStored, fieldUsagesStored, indexBuildMs,
                tablesResolved, tablesAmbiguous, tablesUnresolved
        );
    }

    public static UsageCatalogStatus indexing(boolean catalogEnabled, List<String> sources) {
        return new UsageCatalogStatus(
                catalogEnabled, "indexing", true, sources,
                Instant.now().toString(),
                0, 0, 0, 0, List.of(), List.of(),
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null
        );
    }

    public static UsageCatalogStatus ready(boolean catalogEnabled, String state, List<String> sources,
                                           String startedAt, int filesScanned, int recordsLoaded,
                                           int invalidFiles, int duplicateUids,
                                           List<String> errors, List<String> duplicates,
                                           String lastSuccessfulBuildAt, String finishedAt,
                                           long totalBuildMs,
                                           Integer parseFailed, Integer paramsStored,
                                           Integer tablesExtracted, Integer columnsExtracted,
                                           Integer joinPairsExtracted, Integer outputsStored,
                                           Integer fieldUsagesStored, Long indexBuildMs,
                                           Integer resolved, Integer ambiguous, Integer unresolved) {
        return new UsageCatalogStatus(
                catalogEnabled, state, "indexing".equals(state), sources,
                startedAt, filesScanned, recordsLoaded, invalidFiles, duplicateUids,
                errors, duplicates,
                lastSuccessfulBuildAt, finishedAt, totalBuildMs,
                parseFailed, paramsStored, tablesExtracted, columnsExtracted,
                joinPairsExtracted, outputsStored, fieldUsagesStored, indexBuildMs,
                resolved, ambiguous, unresolved
        );
    }
}