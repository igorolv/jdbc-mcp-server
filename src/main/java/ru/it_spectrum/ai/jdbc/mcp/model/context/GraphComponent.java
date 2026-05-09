package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record GraphComponent(
        int size,
        List<String> tables
) {
}
