package ru.it_spectrum.ai.jdbc.mcp.model.query;

import java.util.List;

public record QueryFeatures(
        boolean selectStar,
        boolean setOperation,
        boolean groupBy,
        boolean limitOrFetch,
        boolean offset,
        boolean selectInto,
        boolean forUpdate,
        List<String> functions
) {
}
