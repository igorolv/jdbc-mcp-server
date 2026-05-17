package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Standard response shape returned by usage-catalog tools when the catalog is disabled.")
public record UsageCatalogDisabledResponse(
        @Schema(description = "Message explaining that the usage catalog is disabled for this server.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String error,
        @Schema(description = "Error category for disabled usage-catalog responses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String kind,
        @Schema(description = "Usage-catalog tool that produced the disabled-catalog response.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String tool,
        @Schema(description = "False when the usage catalog is disabled; true when indexing and lookups are allowed.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean catalogEnabled,
        @Schema(description = "Always zero for disabled-catalog collection responses.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer count,
        @Schema(description = "Usage-catalog query entries returned by the current filter or disabled-catalog response.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<?> queries,
        @Schema(description = "Usage-catalog matches returned for the requested table, column, or filter.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<?> matches,
        @Schema(description = "Relationship edges relevant to the context, graph, or observed-relationships result.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<?> relationships,
        @Schema(description = "Business tags attached to the usage-catalog query.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<?> tags,
        @Schema(description = "Known business domains and their usage counts.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<?> domains,
        @Schema(description = "Known source-kind values and their usage counts.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
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