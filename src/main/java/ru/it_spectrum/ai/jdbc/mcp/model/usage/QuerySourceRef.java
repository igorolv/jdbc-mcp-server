package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Stable pointer to a query record in the usage catalog.")
public record QuerySourceRef(
        @Schema(description = "Kind of source that produced the catalog query, such as file, view, routine, or configured import.", nullable = true)
        String sourceKind,
        @Schema(description = "Path or database object name where the catalog query came from.", nullable = true)
        String sourcePath,
        @Schema(description = "Stable unit identifier inside the source, such as query id, method name, view name, or routine name.", nullable = true)
        String sourceUnit
) {
    public QuerySourceRef {
        if (sourceKind == null || sourceKind.isBlank()) {
            throw new IllegalArgumentException("sourceKind is required");
        }
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath is required");
        }
    }
}
