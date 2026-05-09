package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import java.util.List;

public record LineagePhysicalTable(
        String schema,
        String name,
        String type,
        List<String> via,
        int depth
) {
}
