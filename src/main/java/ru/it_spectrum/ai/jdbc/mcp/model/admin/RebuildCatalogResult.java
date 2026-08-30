package ru.it_spectrum.ai.jdbc.mcp.model.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;

import java.util.List;

@Schema(description = "Outcome of a full catalog rebuild: the persistent structure snapshot plus the "
        + "usage index, both written into the local <catalog>.db so the file can be distributed.")
public record RebuildCatalogResult(
        @Schema(description = "Name of the connection whose catalog was rebuilt.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String connection,
        @Schema(description = "Absolute path of the built catalog file on disk; copy this file to distribute the catalog.", requiredMode = Schema.RequiredMode.REQUIRED)
        String catalogFile,
        @Schema(description = "Schemas captured into the structure snapshot.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> structureSchemas,
        @Schema(description = "Number of schemas captured into the structure snapshot.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int structureSchemaCount,
        @Schema(description = "False when the usage catalog is disabled.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        boolean usageCatalogEnabled,
        @Schema(description = "Usage-index state after the rebuild, e.g. not_started, indexing, ready, failed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String usageCatalogState,
        @Schema(description = "Number of usage-catalog sources considered for indexing.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int usageSourceCount
) {
    /**
     * Flattens the nested {@link UsageCatalogStatus} into scalar fields so the generated output
     * schema stays small (this tool is in the default {@code admin} group). Callers pass the full
     * status; only a few summary values are surfaced.
     */
    public RebuildCatalogResult(String connection, String catalogFile, List<String> structureSchemas,
                                UsageCatalogStatus usage) {
        this(connection, catalogFile, structureSchemas, structureSchemas == null ? 0 : structureSchemas.size(),
                usage != null && usage.catalogEnabled(),
                usage == null ? null : usage.state(),
                usage == null || usage.sources() == null ? 0 : usage.sources().size());
    }
}
