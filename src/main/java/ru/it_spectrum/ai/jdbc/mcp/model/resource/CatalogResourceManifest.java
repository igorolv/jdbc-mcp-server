package ru.it_spectrum.ai.jdbc.mcp.model.resource;

import java.util.List;

/**
 * Stable entry point for the resources exposed by one configured JDBC catalog.
 */
public record CatalogResourceManifest(
        int resourceSchemaVersion,
        String catalog,
        String databaseKind,
        CatalogSnapshotInfo snapshot,
        List<ResourceTemplateRef> resourceTemplates
) {
    public CatalogResourceManifest {
        resourceTemplates = resourceTemplates == null ? List.of() : List.copyOf(resourceTemplates);
    }

    public record ResourceTemplateRef(
            String name,
            String uriTemplate,
            String mimeType
    ) {
    }
}
