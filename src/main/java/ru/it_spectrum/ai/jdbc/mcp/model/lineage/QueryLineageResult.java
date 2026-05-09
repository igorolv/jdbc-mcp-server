package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import ru.it_spectrum.ai.jdbc.mcp.model.query.QueryInspection;

import java.util.List;

public record QueryLineageResult(
        QueryInspection inspection,
        List<LineageDirectObject> directObjects,
        List<LineagePhysicalTable> expandedPhysicalTables,
        List<LineageExpandedObject> expandedObjects,
        List<LineageUnresolvedObject> unresolvedObjects,
        List<LineageCycle> cycles,
        List<LineageWarning> warnings,
        int maxDepth
) {
}
