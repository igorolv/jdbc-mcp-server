package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "InvalidateUsageCatalogCacheResult response payload.")
public record InvalidateUsageCatalogCacheResult(
        @Schema(description = "Records Loaded.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int recordsLoaded
) {
}