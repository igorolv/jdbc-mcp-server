package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import java.util.List;

public record LineageExpandedObject(
        String schema,
        String name,
        String type,
        List<LineageObjectRef> dependsOn,
        List<String> via,
        int depth,
        String confidence
) {
}
