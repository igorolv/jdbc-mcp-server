package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ForeignKey response payload.")
public record ForeignKey(
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Columns.", nullable = true)
        List<String> columns,
        @Schema(description = "Referenced Schema.", nullable = true)
        String referencedSchema,
        @Schema(description = "Referenced Table.", nullable = true)
        String referencedTable,
        @Schema(description = "Referenced Columns.", nullable = true)
        List<String> referencedColumns
) {
}
