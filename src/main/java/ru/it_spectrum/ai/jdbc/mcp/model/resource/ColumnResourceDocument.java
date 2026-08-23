package ru.it_spectrum.ai.jdbc.mcp.model.resource;

import ru.it_spectrum.ai.jdbc.mcp.model.metadata.CheckConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.IncomingForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.UniqueConstraint;

import java.util.List;

/**
 * Focused structural context for one column, including every table-level object that uses it.
 */
public record ColumnResourceDocument(
        int resourceSchemaVersion,
        String catalog,
        String schema,
        String table,
        Column column,
        Integer primaryKeyPosition,
        List<UniqueConstraint> uniqueConstraints,
        List<Index> indexes,
        List<ForeignKey> outgoingForeignKeys,
        List<IncomingForeignKey> incomingForeignKeys,
        List<CheckConstraint> checkConstraints
) {
    public ColumnResourceDocument {
        uniqueConstraints = copy(uniqueConstraints);
        indexes = copy(indexes);
        outgoingForeignKeys = copy(outgoingForeignKeys);
        incomingForeignKeys = copy(incomingForeignKeys);
        checkConstraints = copy(checkConstraints);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
