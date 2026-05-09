package ru.it_spectrum.ai.jdbc.mcp.model.query;

import java.util.List;

public record QueryPredicate(
        String scope,
        String expression,
        String operator,
        List<QueryColumnRef> columns
) {
}
