package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "UsageCatalogDisabledResponse response payload.")
public record UsageCatalogDisabledResponse(
        @Schema(description = "Error.", nullable = true)
        String error,
        @Schema(description = "Kind.", nullable = true)
        String kind,
        @Schema(description = "Tool.", nullable = true)
        String tool,
        @Schema(description = "Catalog Enabled.", nullable = true)
        Boolean catalogEnabled,
        @Schema(description = "Count.", nullable = true)
        Integer count,
        @Schema(description = "Queries.", nullable = true)
        List<?> queries,
        @Schema(description = "Matches.", nullable = true)
        List<?> matches,
        @Schema(description = "Relationships.", nullable = true)
        List<?> relationships,
        @Schema(description = "Tags.", nullable = true)
        List<?> tags,
        @Schema(description = "Domains.", nullable = true)
        List<?> domains,
        @Schema(description = "Kinds.", nullable = true)
        List<?> kinds
) {
    private static final String DISABLED_ERROR =
            "usage catalog is disabled (set JDBC_USAGE_CATALOG_ENABLED=true to enable)";

    public static UsageCatalogDisabledResponse disabled(String tool) {
        return new UsageCatalogDisabledResponse(
                DISABLED_ERROR, "disabled", tool, null, null,
                null, null, null, null, null, null
        );
    }

    public static UsageCatalogDisabledResponse withRows(String tool, String collectionField) {
        List<?> empty = List.of();
        return switch (collectionField) {
            case "queries" -> rows(tool, empty, null, null, null, null, null);
            case "matches" -> rows(tool, null, empty, null, null, null, null);
            case "relationships" -> rows(tool, null, null, empty, null, null, null);
            case "tags" -> rows(tool, null, null, null, empty, null, null);
            case "domains" -> rows(tool, null, null, null, null, empty, null);
            case "kinds" -> rows(tool, null, null, null, null, null, empty);
            default -> throw new IllegalArgumentException("Unknown usage catalog collection: " + collectionField);
        };
    }

    private static UsageCatalogDisabledResponse rows(
            String tool,
            List<?> queries,
            List<?> matches,
            List<?> relationships,
            List<?> tags,
            List<?> domains,
            List<?> kinds
    ) {
        return new UsageCatalogDisabledResponse(
                null, null, tool, false, 0,
                queries, matches, relationships, tags, domains, kinds
        );
    }
}