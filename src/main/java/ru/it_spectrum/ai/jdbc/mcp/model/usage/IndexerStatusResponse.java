package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.time.Instant;
import java.util.List;

public record IndexerStatusResponse(
        boolean catalog_enabled,
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

    public static IndexerStatusResponse initial(boolean catalogEnabled, List<String> sources) {
        return new IndexerStatusResponse(
                catalogEnabled, "not_started", false, sources, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null
        );
    }

    public IndexerStatusResponse withState(String state) {
        return new IndexerStatusResponse(
                catalog_enabled, state, indexing, sources, startedAt, filesScanned, recordsLoaded,
                invalidFiles, duplicateUids, errors, duplicates, lastSuccessfulBuildAt, finishedAt,
                totalBuildMs, parseFailed, paramsStored, tablesExtracted, columnsExtracted,
                joinPairsExtracted, outputsStored, fieldUsagesStored, indexBuildMs,
                tablesResolved, tablesAmbiguous, tablesUnresolved
        );
    }

    public static IndexerStatusResponse indexing(boolean catalogEnabled, List<String> sources) {
        return new IndexerStatusResponse(
                catalogEnabled, "indexing", true, sources,
                Instant.now().toString(),
                0, 0, 0, 0, List.of(), List.of(),
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null
        );
    }

    public static IndexerStatusResponse ready(boolean catalogEnabled, String state, List<String> sources,
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
        return new IndexerStatusResponse(
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
