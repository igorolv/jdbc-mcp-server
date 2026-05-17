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

@Schema(description = "CompactTable response payload.")
public record CompactTable(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Remarks.", nullable = true)
        String remarks,
        @Schema(description = "Columns.", nullable = true)
        List<CompactColumn> columns,
        @Schema(description = "Primary Key.", nullable = true)
        PrimaryKey primaryKey,
        @Schema(description = "Constraints.", nullable = true)
        List<Constraint> constraints,
        @Schema(description = "Allowed Values.", nullable = true)
        Map<String, List<String>> allowedValues,
        @Schema(description = "Foreign Keys.", nullable = true)
        List<ForeignKey> foreignKeys,
        @Schema(description = "Indexes.", nullable = true)
        List<CompactIndex> indexes,
        @Schema(description = "Triggers.", nullable = true)
        List<Trigger> triggers,
        @Schema(description = "Stats.", nullable = true)
        TableStats stats,
        @Schema(description = "Evidence.", nullable = true)
        TableEvidenceProfile evidence

) implements ContextTable {
    public CompactTable withEvidence(TableEvidenceProfile evidence) {
        return new CompactTable(
                schema, name, type, remarks, columns, primaryKey, constraints,
                allowedValues, foreignKeys, indexes, triggers, stats, evidence);
    }
    @Schema(description = "CompactColumn response payload.")
    public record CompactColumn(
            @Schema(description = "Name.", nullable = true)
            String name,
            @Schema(description = "Type.", nullable = true)
            String type,
            @Schema(description = "Nullable.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean nullable,
            @Schema(description = "Primary Key.", nullable = true)
            Boolean primaryKey,
            @Schema(description = "Foreign Key.", nullable = true)
            Boolean foreignKey,
            @Schema(description = "Indexed.", nullable = true)
            Boolean indexed
    ) {
    }
    @Schema(description = "CompactIndex response payload.")
    public record CompactIndex(
            @Schema(description = "Name.", nullable = true)
            String name,
            @Schema(description = "Unique.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            boolean unique,
            @Schema(description = "Columns.", nullable = true)
            List<String> columns
    ) {
    }
}
