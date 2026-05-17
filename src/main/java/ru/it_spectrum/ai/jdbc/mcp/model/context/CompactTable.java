package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Constraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.TableEvidenceProfile;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.TableStats;

import java.util.List;
import java.util.Map;

@Schema(description = "Compact table context optimized for LLM SQL authoring: metadata, keys, constraints, indexes, triggers, optional stats, and usage evidence.")
public record CompactTable(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true)
        String type,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true)
        String remarks,
        @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true)
        List<CompactColumn> columns,
        @Schema(description = "Primary key metadata for the table, null when no primary key is declared or visible.", nullable = true)
        PrimaryKey primaryKey,
        @Schema(description = "Relevant table constraints, including checks, unique constraints, and foreign-key metadata.", nullable = true)
        List<Constraint> constraints,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true)
        Map<String, List<String>> allowedValues,
        @Schema(description = "Outgoing foreign keys declared by the table.", nullable = true)
        List<ForeignKey> foreignKeys,
        @Schema(description = "Indexes available on the table or returned by an index-statistics scan.", nullable = true)
        List<CompactIndex> indexes,
        @Schema(description = "Triggers attached to the table, usually without full body unless requested.", nullable = true)
        List<Trigger> triggers,
        @Schema(description = "Optional live table statistics and storage metrics.", nullable = true)
        TableStats stats,
        @Schema(description = "Evidence explaining why this object or relationship is relevant, from schema and usage sources.", nullable = true)
        TableEvidenceProfile evidence

) implements ContextTable {
    public CompactTable withEvidence(TableEvidenceProfile evidence) {
        return new CompactTable(
                schema, name, type, remarks, columns, primaryKey, constraints,
                allowedValues, foreignKeys, indexes, triggers, stats, evidence);
    }
    @Schema(description = "Compact column metadata used inside table context responses.")
    public record CompactColumn(
            @Schema(description = "Column name as reported by database metadata.", nullable = true)
            String name,
            @Schema(description = "Database type name for the column.", nullable = true)
            String type,
            @Schema(description = "True when the column accepts NULL values.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean nullable,
            @Schema(description = "True when the column participates in the table primary key.", nullable = true)
            Boolean primaryKey,
            @Schema(description = "True when the column participates in an outgoing foreign key.", nullable = true)
            Boolean foreignKey,
            @Schema(description = "True when the column is covered by at least one visible index.", nullable = true)
            Boolean indexed
    ) {
    }
    @Schema(description = "Compact index metadata used inside table context responses.")
    public record CompactIndex(
            @Schema(description = "Index name as reported by database metadata.", nullable = true)
            String name,
            @Schema(description = "True when the index or constraint enforces uniqueness.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean unique,
            @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true)
            List<String> columns
    ) {
    }
}
