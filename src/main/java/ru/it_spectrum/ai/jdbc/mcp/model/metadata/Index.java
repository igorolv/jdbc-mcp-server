package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import java.util.List;

public record Index(String name, boolean unique, List<String> columns) {
}
