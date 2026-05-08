package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import java.util.List;

public record PrimaryKey(String name, List<String> columns) {
}
