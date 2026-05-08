package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import java.util.List;

public record ForeignKey(
        String name,
        List<String> columns,
        String referencedSchema,
        String referencedTable,
        List<String> referencedColumns
) {
}
