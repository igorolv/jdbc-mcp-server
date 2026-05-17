package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;

import java.util.List;
import java.util.Map;

@Schema(description = "Table packet selected for query authoring, combining structural metadata, relevant columns, evidence, and optional sample rows.")
public record QueryContextTable(
        @Schema(description = "Database schema or owner that qualifies the object.", nullable = true)
        String schema,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Database object type, SQL construct type, or engine-specific classification.", nullable = true)
        String type,
        @Schema(description = "Heuristic table role in the schema graph, such as central, isolated, lookup, or regular.", nullable = true)
        String classification,
        @Schema(description = "Database comment or description attached to the object, when the driver exposes it.", nullable = true)
        String remarks,
        @Schema(description = "Primary key metadata for the table, null when no primary key is declared or visible.", nullable = true)
        PrimaryKey primaryKey,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true)
        Map<String, List<String>> allowedValues,
        @Schema(description = "Columns selected as most useful for query authoring from metadata and semantic matches.", nullable = true)
        List<QueryContextColumn> relevantColumns,
        @Schema(description = "Relevant table constraints, including checks, unique constraints, and foreign-key metadata.", nullable = true)
        List<QueryContextConstraint> constraints,
        @Schema(description = "Outgoing foreign keys declared by the table.", nullable = true)
        List<ForeignKey> foreignKeys,
        @Schema(description = "Indexes available on the table or returned by an index-statistics scan.", nullable = true)
        List<CompactTable.CompactIndex> indexes,
        @Schema(description = "Semantic match evidence that caused this table to be selected for query context.", nullable = true)
        SemanticTableCandidate semanticMatch,
        @Schema(description = "Small sample of table rows included for data-shape inspection.", nullable = true)
        QueryContextSample sample
) {
}
