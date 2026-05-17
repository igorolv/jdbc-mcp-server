package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "Full table or view description in one payload: columns, keys, indexes, constraints, allowed values, references, and triggers.")
public record TableDescription(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String name,
        @Schema(description = "Described object type, such as TABLE, VIEW, or MATERIALIZED VIEW.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String type,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String remarks,
        @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Column> columns,
        @Schema(description = "Primary key metadata for the table, null when no primary key is declared or visible.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        PrimaryKey primaryKey,
        @Schema(description = "Unique constraints declared on the table.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<UniqueConstraint> uniqueConstraints,
        @Schema(description = "Indexes available on the table or returned by an index-statistics scan.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Index> indexes,
        @Schema(description = "Outgoing foreign keys declared by the table.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<ForeignKey> foreignKeys,
        @Schema(description = "Incoming foreign keys from other tables that reference this table.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<IncomingForeignKey> referencedBy,
        @Schema(description = "Relevant table constraints, including checks, unique constraints, and foreign-key metadata.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Constraint> constraints,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Map<String, List<String>> allowedValues,
        @Schema(description = "Triggers attached to the table, usually without full body unless requested.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<Trigger> triggers
) {
}
