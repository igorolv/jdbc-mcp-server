package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "QueryDetail response payload.")
public record QueryDetail(
        @Schema(description = "Source Kind.", nullable = true)
        String sourceKind,
        @Schema(description = "Source Path.", nullable = true)
        String sourcePath,
        @Schema(description = "Source Unit.", nullable = true)
        String sourceUnit,
        @Schema(description = "Business Label.", nullable = true)
        String businessLabel,
        @Schema(description = "Business Domain.", nullable = true)
        String businessDomain,
        @Schema(description = "Raw Sql.", nullable = true)
        String rawSql,
        @Schema(description = "Normalized Sql.", nullable = true)
        String normalizedSql,
        @Schema(description = "Parse Status.", nullable = true)
        String parseStatus,
        @Schema(description = "Tags.", nullable = true)
        List<String> tags,
        @Schema(description = "Parameters.", nullable = true)
        List<Param> parameters,
        @Schema(description = "Tables.", nullable = true)
        List<Table> tables,
        @Schema(description = "Columns.", nullable = true)
        List<Column> columns,
        @Schema(description = "Join Pairs.", nullable = true)
        List<JoinPair> joinPairs,
        @Schema(description = "Outputs.", nullable = true)
        List<Output> outputs,
        @Schema(description = "Field Usages.", nullable = true)
        List<FieldUsage> fieldUsages
) {
    @Schema(description = "Param response payload.")
    public record Param(
            @Schema(description = "Ordinal.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int ordinal,
            @Schema(description = "Name.", nullable = true)
            String name,
            @Schema(description = "Data Type.", nullable = true)
            String dataType,
            @Schema(description = "Default Value.", nullable = true)
            String defaultValue,
            @Schema(description = "Required.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean required,
            @Schema(description = "Business Label.", nullable = true)
            String businessLabel,
            @Schema(description = "Business Description.", nullable = true)
            String businessDescription
    ) {
    }
    @Schema(description = "Table response payload.")
    public record Table(
            @Schema(description = "Raw Name.", nullable = true)
            String rawName,
            @Schema(description = "Schema Resolved.", nullable = true)
            String schemaResolved,
            @Schema(description = "Table Resolved.", nullable = true)
            String tableResolved,
            @Schema(description = "Alias.", nullable = true)
            String alias,
            @Schema(description = "Role.", nullable = true)
            String role,
            @Schema(description = "Resolution Status.", nullable = true)
            String resolutionStatus
    ) {
    }
    @Schema(description = "Column response payload.")
    public record Column(
            @Schema(description = "Schema Resolved.", nullable = true)
            String schemaResolved,
            @Schema(description = "Table Resolved.", nullable = true)
            String tableResolved,
            @Schema(description = "Column Name.", nullable = true)
            String columnName,
            @Schema(description = "Context.", nullable = true)
            String context
    ) {
    }
    @Schema(description = "JoinPair response payload.")
    public record JoinPair(
            @Schema(description = "Join Type.", nullable = true)
            String joinType,
            @Schema(description = "Left.", nullable = true)
            SchemaRef left,
            @Schema(description = "Right.", nullable = true)
            SchemaRef right,
            @Schema(description = "On Text.", nullable = true)
            String onText,
            @Schema(description = "Equality.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean equality
    ) {
        @Schema(description = "SchemaRef response payload.")
        public record SchemaRef(
                @Schema(description = "Schema.", nullable = true)
                String schema,
                @Schema(description = "Table.", nullable = true)
                String table,
                @Schema(description = "Column.", nullable = true)
                String column
        ) {
        }
    }
    @Schema(description = "Output response payload.")
    public record Output(
            @Schema(description = "Alias.", nullable = true)
            String alias,
            @Schema(description = "Source Expression.", nullable = true)
            String sourceExpression,
            @Schema(description = "Business Label.", nullable = true)
            String businessLabel,
            @Schema(description = "Business Description.", nullable = true)
            String businessDescription,
            @Schema(description = "Derived From Columns.", nullable = true)
            List<DerivedColumn> derivedFromColumns
    ) {
        @Schema(description = "DerivedColumn response payload.")
        public record DerivedColumn(
                @Schema(description = "Schema.", nullable = true)
                String schema,
                @Schema(description = "Table.", nullable = true)
                String table,
                @Schema(description = "Column.", nullable = true)
                String column
        ) {
        }
    }
    @Schema(description = "FieldUsage response payload.")
    public record FieldUsage(
            @Schema(description = "Business Object.", nullable = true)
            String businessObject,
            @Schema(description = "Transformation.", nullable = true)
            Transformation transformation,
            @Schema(description = "Location.", nullable = true)
            Location location,
            @Schema(description = "Confidence.", nullable = true)
            String confidence
    ) {
        @Schema(description = "Transformation response payload.")
        public record Transformation(
                @Schema(description = "Kind.", nullable = true)
                String kind,
                @Schema(description = "Description.", nullable = true)
                String description
        ) {
        }
        @Schema(description = "Location response payload.")
        public record Location(
                @Schema(description = "Kind.", nullable = true)
                String kind
        ) {
        }
    }
}