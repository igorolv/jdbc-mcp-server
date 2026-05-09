package ru.it_spectrum.ai.jdbc.mcp.model.context;

import java.util.List;

public record ShortestPath(
        String from,
        String to,
        boolean found,
        List<JoinPathStep> edges
) {
}
