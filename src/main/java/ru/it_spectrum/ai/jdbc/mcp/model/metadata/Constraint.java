package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Table constraint metadata, including check definitions, allowed values, or referenced objects for foreign keys.")
public record Constraint(
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Constraint type, such as PRIMARY KEY, UNIQUE, FOREIGN KEY, or CHECK.", nullable = true)
        String type,
        @Schema(description = "Column list in database order; for keys, indexes, and joins the order is significant.", nullable = true)
        List<String> columns,
        @Schema(description = "SQL definition or constraint expression as reported by the database.", nullable = true)
        String definition,
        @Schema(description = "Column whose allowed values were parsed from a CHECK constraint.", nullable = true)
        String allowedValuesColumn,
        @Schema(description = "Allowed values extracted from CHECK constraints, keyed by column name when available.", nullable = true)
        List<String> allowedValues,
        @Schema(description = "Schema of the table referenced by a foreign key or constraint.", nullable = true)
        String referencedSchema,
        @Schema(description = "Table referenced by a foreign key or constraint.", nullable = true)
        String referencedTable,
        @Schema(description = "Columns referenced by the foreign key or constraint, in key order.", nullable = true)
        List<String> referencedColumns
) {
}
