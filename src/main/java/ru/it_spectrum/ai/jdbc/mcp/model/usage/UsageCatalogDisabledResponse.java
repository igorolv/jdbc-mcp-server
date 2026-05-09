package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record UsageCatalogDisabledResponse(
        String error,
        String kind,
        String tool,
        Boolean catalog_enabled,
        Integer count,
        List<Object> queries,
        List<Object> matches,
        List<Object> relationships,
        List<Object> tags,
        List<Object> domains
) {
    private static final String DISABLED_ERROR =
            "usage catalog is disabled (set JDBC_USAGE_CATALOG_ENABLED=true to enable)";

    public static UsageCatalogDisabledResponse disabled(String tool) {
        return new UsageCatalogDisabledResponse(
                DISABLED_ERROR, "disabled", tool, null, null,
                null, null, null, null, null
        );
    }

    public static UsageCatalogDisabledResponse withRows(String tool, String collectionField) {
        List<Object> empty = List.of();
        return switch (collectionField) {
            case "queries" -> rows(tool, empty, null, null, null, null);
            case "matches" -> rows(tool, null, empty, null, null, null);
            case "relationships" -> rows(tool, null, null, empty, null, null);
            case "tags" -> rows(tool, null, null, null, empty, null);
            case "domains" -> rows(tool, null, null, null, null, empty);
            default -> throw new IllegalArgumentException("Unknown usage catalog collection: " + collectionField);
        };
    }

    private static UsageCatalogDisabledResponse rows(
            String tool,
            List<Object> queries,
            List<Object> matches,
            List<Object> relationships,
            List<Object> tags,
            List<Object> domains
    ) {
        return new UsageCatalogDisabledResponse(
                null, null, tool, false, 0,
                queries, matches, relationships, tags, domains
        );
    }
}
