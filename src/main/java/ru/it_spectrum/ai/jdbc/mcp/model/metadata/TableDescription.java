package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "TableDescription response payload.")
public record TableDescription(
        @Schema(description = "Schema.", nullable = true)
        String schema,
        @Schema(description = "Name.", nullable = true)
        String name,
        @Schema(description = "Type.", nullable = true)
        String type,
        @Schema(description = "Remarks.", nullable = true)
        String remarks,
        @Schema(description = "Columns.", nullable = true)
        List<Column> columns,
        @Schema(description = "Primary Key.", nullable = true)
        PrimaryKey primaryKey,
        @Schema(description = "Unique Constraints.", nullable = true)
        List<UniqueConstraint> uniqueConstraints,
        @Schema(description = "Indexes.", nullable = true)
        List<Index> indexes,
        @Schema(description = "Foreign Keys.", nullable = true)
        List<ForeignKey> foreignKeys,
        @Schema(description = "Referenced By.", nullable = true)
        List<IncomingForeignKey> referencedBy,
        @Schema(description = "Constraints.", nullable = true)
        List<Constraint> constraints,
        @Schema(description = "Allowed Values.", nullable = true)
        Map<String, List<String>> allowedValues,
        @Schema(description = "Triggers.", nullable = true)
        List<Trigger> triggers
) {
}
