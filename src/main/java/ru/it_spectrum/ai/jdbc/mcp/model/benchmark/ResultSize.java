package ru.it_spectrum.ai.jdbc.mcp.model.benchmark;

import java.util.List;

public record ResultSize(
        int rowCount,
        boolean truncated,
        List<String> columns,
        List<String> columnTypes
) {
}