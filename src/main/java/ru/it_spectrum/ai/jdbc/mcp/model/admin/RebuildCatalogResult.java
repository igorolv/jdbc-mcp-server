package ru.it_spectrum.ai.jdbc.mcp.model.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;

import java.util.List;

@Schema(description = "Outcome of a full catalog rebuild: the persistent structure snapshot plus the "
        + "usage index, both written into the local <catalog>.db so the file can be distributed.")
public record RebuildCatalogResult(
        @Schema(description = "Absolute path of the built catalog file on disk; copy this file to distribute the catalog.", requiredMode = Schema.RequiredMode.REQUIRED)
        String catalogFile,
        @Schema(description = "Schemas captured into the structure snapshot.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<String> structureSchemas,
        @Schema(description = "Number of schemas captured into the structure snapshot.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        int structureSchemaCount,
        @Schema(description = "Status of the usage index after the rebuild.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        UsageCatalogStatus usage
) {
    public RebuildCatalogResult(String catalogFile, List<String> structureSchemas, UsageCatalogStatus usage) {
        this(catalogFile, structureSchemas, structureSchemas == null ? 0 : structureSchemas.size(), usage);
    }
}
