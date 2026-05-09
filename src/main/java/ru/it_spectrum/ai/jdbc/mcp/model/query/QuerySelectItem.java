package ru.it_spectrum.ai.jdbc.mcp.model.query;

import java.util.List;

public record QuerySelectItem(
        String expression,
        String alias,
        Boolean star,
        List<QueryColumnRef> columns
) {
}
