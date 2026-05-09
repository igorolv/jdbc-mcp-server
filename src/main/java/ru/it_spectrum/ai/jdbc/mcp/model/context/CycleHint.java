package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record CycleHint(
        List<String> tables,
        String note
) {
}
