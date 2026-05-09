package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import java.util.List;

public record LineageUnresolvedObject(
        String schema,
        String name,
        String kind,
        String source,
        String reason,
        List<LineageObjectRef> candidates,
        List<String> via
) {
}
