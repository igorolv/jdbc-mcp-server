package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "Full table or view description in one payload: columns, keys, indexes, constraints, allowed values, references, and triggers.")
public record TableDescription(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Described object type, such as TABLE, VIEW, or MATERIALIZED VIEW.", nullable = true)
        String type,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true)
        String remarks,
        @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true)
        List<Column> columns,
        @Schema(description = "Primary key metadata for the table, null when no primary key is declared or visible.", nullable = true)
        PrimaryKey primaryKey,
        @Schema(description = "Unique constraints declared on the table.", nullable = true)
        List<UniqueConstraint> uniqueConstraints,
        @Schema(description = "Indexes available on the table or returned by an index-statistics scan.", nullable = true)
        List<Index> indexes,
        @Schema(description = "Outgoing foreign keys declared by the table.", nullable = true)
        List<ForeignKey> foreignKeys,
        @Schema(description = "Incoming foreign keys from other tables that reference this table.", nullable = true)
        List<IncomingForeignKey> referencedBy,
        @Schema(description = "Relevant table constraints, including checks, unique constraints, and foreign-key metadata.", nullable = true)
        List<Constraint> constraints,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true)
        Map<String, List<String>> allowedValues,
        @Schema(description = "Triggers attached to the table, usually without full body unless requested.", nullable = true)
        List<Trigger> triggers
) {
}
