package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record CatalogQueryDetail(
        String sourceKind,
        String sourcePath,
        String sourceUnit,
        String businessLabel,
        String businessDomain,
        String rawSql,
        String normalizedSql,
        String parseStatus,
        String parseError,
        String sourceMetaJson,
        String ingestedAt,
        List<String> tags,
        List<Param> parameters,
        List<Table> tables,
        List<Column> columns,
        List<JoinPair> joinPairs,
        List<Output> outputs,
        List<FieldUsage> fieldUsages
) {
    public record Param(
            int ordinal,
            String name,
            String dataType,
            String defaultValue,
            boolean required,
            String businessLabel,
            String businessDescription
    ) {
    }

    public record Table(
            String rawName,
            String schemaResolved,
            String tableResolved,
            String alias,
            String role,
            String resolutionStatus
    ) {
    }

    public record Column(
            String schemaResolved,
            String tableResolved,
            String columnName,
            String context
    ) {
    }

    public record JoinPair(
            String joinType,
            SchemaRef left,
            SchemaRef right,
            String onText,
            boolean equality
    ) {
        public record SchemaRef(String schema, String table, String column) {
        }
    }

    public record Output(
            String alias,
            String sourceExpression,
            String businessLabel,
            String businessDescription,
            List<DerivedColumn> derivedFromColumns
    ) {
        public record DerivedColumn(String schema, String table, String column) {
        }
    }

    public record FieldUsage(
            Long outputId,
            String businessObject,
            Transformation transformation,
            Location location,
            String headersJson,
            String confidence
    ) {
        public record Transformation(String kind, String description) {
        }

        public record Location(String kind, String detailsJson) {
        }
    }
}