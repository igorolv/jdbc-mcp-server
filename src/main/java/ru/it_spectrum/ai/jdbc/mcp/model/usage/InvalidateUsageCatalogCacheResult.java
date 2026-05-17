package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of rebuilding the usage catalog cache after invalidation.")
public record InvalidateUsageCatalogCacheResult(
        @Schema(description = "Number of usage-catalog records loaded after cache invalidation.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int recordsLoaded
) {
}