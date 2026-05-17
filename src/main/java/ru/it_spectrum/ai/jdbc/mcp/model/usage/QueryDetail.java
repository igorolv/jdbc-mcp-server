package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Full usage-catalog record for one known query, including SQL text, parsed references, joins, outputs, and semantic field usages.")
public record QueryDetail(
        @Schema(description = "Kind of source that produced the catalog query, such as file, view, routine, or configured import.", nullable = true)
        String sourceKind,
        @Schema(description = "Path or database object name where the catalog query came from.", nullable = true)
        String sourcePath,
        @Schema(description = "Stable unit identifier inside the source, such as query id, method name, view name, or routine name.", nullable = true)
        String sourceUnit,
        @Schema(description = "Human-readable business label attached to the query, parameter, or output.", nullable = true)
        String businessLabel,
        @Schema(description = "Business domain assigned to the catalog query or usage record.", nullable = true)
        String businessDomain,
        @Schema(description = "Original SQL text stored in the usage catalog.", nullable = true)
        String rawSql,
        @Schema(description = "Normalized SQL text produced by parser or usage-catalog indexing.", nullable = true)
        String normalizedSql,
        @Schema(description = "SQL parse status for a catalog query, such as parsed or failed.", nullable = true)
        String parseStatus,
        @Schema(description = "Business tags attached to the usage-catalog query.", nullable = true)
        List<String> tags,
        @Schema(description = "Documented parameters for this known query.", nullable = true)
        List<Param> parameters,
        @Schema(description = "Tables included in this context, graph, query inspection, or usage record.", nullable = true)
        List<Table> tables,
        @Schema(description = "Resolved column references extracted from the known query.", nullable = true)
        List<Column> columns,
        @Schema(description = "Join pairs extracted from the query and stored in the usage catalog.", nullable = true)
        List<JoinPair> joinPairs,
        @Schema(description = "Documented output fields produced by the query.", nullable = true)
        List<Output> outputs,
        @Schema(description = "Semantic field usages attached to the query outputs or expressions.", nullable = true)
        List<FieldUsage> fieldUsages
) {
    @Schema(description = "Documented query parameter from a usage-catalog record.")
    public record Param(
            @Schema(description = "One-based parameter ordinal in the query signature.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int ordinal,
            @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
            String name,
            @Schema(description = "Documented data type for the parameter.", nullable = true)
            String dataType,
            @Schema(description = "Default value expression for the column or documented parameter.", nullable = true)
            String defaultValue,
            @Schema(description = "True when the documented parameter is required.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean required,
            @Schema(description = "Human-readable business label attached to the query, parameter, or output.", nullable = true)
            String businessLabel,
            @Schema(description = "Free-text business explanation for a parameter, output, or transformation.", nullable = true)
            String businessDescription
    ) {
    }
    @Schema(description = "Parsed table reference from a usage-catalog query record.")
    public record Table(
            @Schema(description = "Raw table name as it appeared in the SQL source before resolution.", nullable = true)
            String rawName,
            @Schema(description = "Schema resolved by parser and metadata matching.", nullable = true)
            String schemaResolved,
            @Schema(description = "Table name resolved by parser and metadata matching.", nullable = true)
            String tableResolved,
            @Schema(description = "SQL alias assigned to the table reference in the catalog query.", nullable = true)
            String alias,
            @Schema(description = "Role of the table reference in SQL, such as from, join, cte, or subquery.", nullable = true)
            String role,
            @Schema(description = "Whether the parser or resolver matched the reference to a database object.", nullable = true)
            String resolutionStatus
    ) {
    }
    @Schema(description = "Resolved column reference extracted from a usage-catalog query record.")
    public record Column(
            @Schema(description = "Schema resolved by parser and metadata matching.", nullable = true)
            String schemaResolved,
            @Schema(description = "Table name resolved by parser and metadata matching.", nullable = true)
            String tableResolved,
            @Schema(description = "Resolved column name referenced by the query.", nullable = true)
            String columnName,
            @Schema(description = "Usage context for the resolved column in the catalog query.", nullable = true)
            String context
    ) {
    }
    @Schema(description = "Parsed join pair from a usage-catalog query record.")
    public record JoinPair(
            @Schema(description = "Join type used for the estimate or parsed join, such as INNER, LEFT, RIGHT, or FULL.", nullable = true)
            String joinType,
            @Schema(description = "Left side of an observed or documented join relationship.", nullable = true)
            SchemaRef left,
            @Schema(description = "Right side of an observed or documented join relationship.", nullable = true)
            SchemaRef right,
            @Schema(description = "Original ON-clause text for the join pair.", nullable = true)
            String onText,
            @Schema(description = "True when the join pair is an equality comparison suitable for relationship inference.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean equality
    ) {
        @Schema(description = "Schema-qualified table column reference used in usage-catalog relationship and lineage details.")
        public record SchemaRef(
                @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
                String schema,
                @Schema(description = "Table name within the schema.", nullable = true)
                String table,
                @Schema(description = "Column name within the table.", nullable = true)
                String column
        ) {
        }
    }
    @Schema(description = "Documented output field from a usage-catalog query record.")
    public record Output(
            @Schema(description = "Output alias or field name exposed by the query.", nullable = true)
            String alias,
            @Schema(description = "SQL expression that produces the documented output field.", nullable = true)
            String sourceExpression,
            @Schema(description = "Human-readable business label attached to the query, parameter, or output.", nullable = true)
            String businessLabel,
            @Schema(description = "Free-text business explanation for a parameter, output, or transformation.", nullable = true)
            String businessDescription,
            @Schema(description = "Source columns that contribute to the output expression.", nullable = true)
            List<DerivedColumn> derivedFromColumns
    ) {
        @Schema(description = "Source column that contributes to a documented output field.")
        public record DerivedColumn(
                @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
                String schema,
                @Schema(description = "Table name within the schema.", nullable = true)
                String table,
                @Schema(description = "Column name within the table.", nullable = true)
                String column
        ) {
        }
    }
    @Schema(description = "Semantic business-object usage attached to a field in a usage-catalog query.")
    public record FieldUsage(
            @Schema(description = "Business object represented by this field usage.", nullable = true)
            String businessObject,
            @Schema(description = "Transformation applied before the business object is produced.", nullable = true)
            Transformation transformation,
            @Schema(description = "Location where the business field usage was found.", nullable = true)
            Location location,
            @Schema(description = "Confidence label for inferred lineage or semantic field usage.", nullable = true)
            String confidence
    ) {
        @Schema(description = "Transformation applied to a source field before it appears as a business object.")
        public record Transformation(
                @Schema(description = "Transformation kind, such as direct, cast, aggregate, expression, or derived.", nullable = true)
                String kind,
                @Schema(description = "Human-readable explanation of the transformation or catalog element.", nullable = true)
                String description
        ) {
        }
        @Schema(description = "Location in the query or output where a business field usage appears.")
        public record Location(
                @Schema(description = "Location kind where the business field usage appears, such as select, where, join, or output.", nullable = true)
                String kind
        ) {
        }
    }
}
