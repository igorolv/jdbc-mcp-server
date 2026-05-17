package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Evidence that a relationship comes from a declared database foreign key.")
public record DeclaredSchemaEdgeEvidence(
        @Schema(description = "Declared foreign-key constraint name supporting this relationship evidence.", nullable = true)
        String foreignKeyName,
        @Schema(description = "Source-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "Target-side columns participating in the relationship, in join/key order.", nullable = true)
        List<String> toColumns
) {
    public DeclaredSchemaEdgeEvidence {
        fromColumns = fromColumns == null ? List.of() : List.copyOf(fromColumns);
        toColumns = toColumns == null ? List.of() : List.copyOf(toColumns);
    }
}
