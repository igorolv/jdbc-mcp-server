package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;

import java.util.List;

@Schema(description = "Usage-catalog evidence showing how a table and its columns are used in known SQL queries.")
public record ObservedTableUsage(
        @Schema(description = "Number of known SQL queries that reference this object or term.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int queryCount,
        @Schema(description = "Usage-catalog source records that support this evidence item.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QuerySourceRef> sourceRefs,
        @Schema(description = "Observed usage details for columns referenced by known queries.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<ObservedColumnUsage> columns
) {
    public ObservedTableUsage {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
