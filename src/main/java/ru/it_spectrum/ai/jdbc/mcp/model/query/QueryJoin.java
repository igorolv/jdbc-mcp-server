package ru.it_spectrum.ai.jdbc.mcp.model.query;

import java.util.List;

public record QueryJoin(
        String type,
        String rightItem,
        String on,
        List<String> using,
        Boolean conditionless
) {
}
