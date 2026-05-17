package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Outgoing foreign key from the described table to a referenced table.")
public record ForeignKey(
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true)
        List<String> columns,
        @Schema(description = "Schema of the table referenced by a foreign key or constraint.", nullable = true)
        String referencedSchema,
        @Schema(description = "Table referenced by a foreign key or constraint.", nullable = true)
        String referencedTable,
        @Schema(description = "Columns referenced by the foreign key or constraint, in key order.", nullable = true)
        List<String> referencedColumns
) {
}
