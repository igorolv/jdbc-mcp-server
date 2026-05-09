package ru.it_spectrum.ai.jdbc.mcp.model.query;

import java.util.List;

public record QueryOrderBy(
        String expression,
        String source,
        List<QueryColumnRef> columns
) {
}
