package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record UsageCatalogDisabledResponse(
        String error,
        String kind,
        String tool,
        Boolean catalog_enabled,
        Integer count,
        List<?> queries,
        List<?> matches,
        List<?> relationships,
        List<?> tags,
        List<?> domains,
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