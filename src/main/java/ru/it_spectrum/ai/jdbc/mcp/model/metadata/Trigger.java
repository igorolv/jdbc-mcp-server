package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import java.util.List;

public record Trigger(
        String schema,
        String table,
        String name,
        String timing,
        List<String> events,
        boolean enabled,
        String definition
) {
}
