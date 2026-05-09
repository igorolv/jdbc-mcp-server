package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import java.util.List;

public record ResultSize(
        int row_count,
        boolean truncated,
        List<String> columns,
        List<String> column_types
) {
}
