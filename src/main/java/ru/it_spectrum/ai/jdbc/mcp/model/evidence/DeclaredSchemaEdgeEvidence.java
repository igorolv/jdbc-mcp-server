package ru.it_spectrum.ai.jdbc.mcp.model.evidence;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "DeclaredSchemaEdgeEvidence response payload.")
public record DeclaredSchemaEdgeEvidence(
        @Schema(description = "Foreign Key Name.", nullable = true)
        String foreignKeyName,
        @Schema(description = "From Columns.", nullable = true)
        List<String> fromColumns,
        @Schema(description = "To Columns.", nullable = true)
        List<String> toColumns
) {
    public DeclaredSchemaEdgeEvidence {
        fromColumns = fromColumns == null ? List.of() : List.copyOf(fromColumns);
        toColumns = toColumns == null ? List.of() : List.copyOf(toColumns);
    }
}
