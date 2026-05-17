package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Query object reference that could not be resolved unambiguously against metadata.")
public record LineageUnresolvedObject(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Kind of unresolved reference, such as table, view, routine, or synonym.", nullable = true)
        String kind,
        @Schema(description = "Where the object or expression was found, such as FROM, JOIN, CTE, SELECT, or ORDER BY.", nullable = true)
        String source,
        @Schema(description = "Reason the object was unresolved or the item was highlighted.", nullable = true)
        String reason,
        @Schema(description = "Possible metadata matches considered when a reference could not be resolved uniquely.", nullable = true)
        List<LineageObjectRef> candidates,
        @Schema(description = "Expansion path showing how this object was reached from the original query reference.", nullable = true)
        List<String> via
) {
}
