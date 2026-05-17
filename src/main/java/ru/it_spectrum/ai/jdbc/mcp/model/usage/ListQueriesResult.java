package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Paged list of usage-catalog query records matching the supplied filters.")
public record ListQueriesResult(
        @Schema(description = "Usage-catalog query entries returned by the current filter or disabled-catalog response.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<QueryEntry> queries,
        @Schema(description = "Row limit applied to the query or page size requested by the caller.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int limit,
        @Schema(description = "Zero-based result offset for pagination, or true when SQL contains OFFSET depending on context.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int offset,
        @Schema(description = "Number of query entries returned in this page.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int count
) {
    @Schema(description = "Compact usage-catalog query listing entry.")
    public record QueryEntry(
            @Schema(description = "Kind of source that produced the catalog query, such as file, view, routine, or configured import.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String sourceKind,
            @Schema(description = "Path or database object name where the catalog query came from.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String sourcePath,
            @Schema(description = "Stable unit identifier inside the source, such as query id, method name, view name, or routine name.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String sourceUnit,
            @Schema(description = "Human-readable business label attached to the query, parameter, or output.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String businessLabel,
            @Schema(description = "Business domain assigned to the catalog query or usage record.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String businessDomain,
            @Schema(description = "SQL parse status for a catalog query, such as parsed or failed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String parseStatus
    ) {
    }
}