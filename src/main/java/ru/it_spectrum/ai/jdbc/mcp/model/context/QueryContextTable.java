package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;

import java.util.List;
import java.util.Map;

@Schema(description = "QueryContextTable response payload.")
public record QueryContextTable(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Classification.", nullable = true)
        String classification,
        @Schema(description = "Remarks.", nullable = true)
        String remarks,
        @Schema(description = "Primary Key.", nullable = true)
        PrimaryKey primaryKey,
        @Schema(description = "Allowed Values.", nullable = true)
        Map<String, List<String>> allowedValues,
        @Schema(description = "Relevant Columns.", nullable = true)
        List<QueryContextColumn> relevantColumns,
        @Schema(description = "Constraints.", nullable = true)
        List<QueryContextConstraint> constraints,
        @Schema(description = "Foreign Keys.", nullable = true)
        List<ForeignKey> foreignKeys,
        @Schema(description = "Indexes.", nullable = true)
        List<CompactTable.CompactIndex> indexes,
        @Schema(description = "Semantic Match.", nullable = true)
        SemanticTableCandidate semanticMatch,
        @Schema(description = "Sample.", nullable = true)
        QueryContextSample sample
) {
}
