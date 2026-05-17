package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "ObservedTableUsage response payload.")
public record ObservedTableUsage(
        @Schema(description = "Query Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int queryCount,
        @Schema(description = "Source Refs.", nullable = true)
        List<QuerySourceRef> sourceRefs,
        @Schema(description = "Columns.", nullable = true)
        List<ObservedColumnUsage> columns
) {
    public ObservedTableUsage {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
