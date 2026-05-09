package ru.it_spectrum.ai.jdbc.mcp.model.lineage;

import java.util.List;

public record LineageCycle(
        List<String> path
) {
}
