package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryInspection;

import java.util.List;

@Schema(description = "Lineage analysis of a query, including direct references, expanded physical tables, unresolved objects, cycles, and warnings.")
public record QueryLineageResult(
        @Schema(description = "Parsed query inspection that underpins validation, lint, or lineage results.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        QueryInspection inspection,
        @Schema(description = "Objects directly referenced by the query before recursive expansion.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<LineageDirectObject> directObjects,
        @Schema(description = "Physical tables reached by recursively expanding views and routines.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<LineagePhysicalTable> expandedPhysicalTables,
        @Schema(description = "All resolved objects visited during lineage expansion.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<LineageExpandedObject> expandedObjects,
        @Schema(description = "Objects that could not be resolved against metadata during lineage analysis.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<LineageUnresolvedObject> unresolvedObjects,
        @Schema(description = "Possible relationship or lineage cycles found during traversal.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<LineageCycle> cycles,
        @Schema(description = "Non-fatal warnings produced while resolving or expanding lineage.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<LineageWarning> warnings,
        @Schema(description = "Maximum relationship traversal depth that was applied.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int maxDepth
) {
}
