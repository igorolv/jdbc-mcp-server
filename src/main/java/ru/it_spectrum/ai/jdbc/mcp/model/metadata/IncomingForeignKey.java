package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import java.util.List;

public record IncomingForeignKey(
        String name,
        String fromSchema,
        String fromTable,
        List<String> fromColumns,
        List<String> toColumns
) {
}
