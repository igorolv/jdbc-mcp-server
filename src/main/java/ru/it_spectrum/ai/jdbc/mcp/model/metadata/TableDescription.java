package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import java.util.List;
import java.util.Map;

public record TableDescription(
        String schema,
        String name,
        String type,
        String remarks,
        List<Column> columns,
        PrimaryKey primaryKey,
        List<UniqueConstraint> uniqueConstraints,
        List<Index> indexes,
        List<ForeignKey> foreignKeys,
        List<IncomingForeignKey> referencedBy,
        List<Constraint> constraints,
        Map<String, List<String>> allowedValues,
        List<Trigger> triggers
) {
}
