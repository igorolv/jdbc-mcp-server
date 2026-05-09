package ru.it_spectrum.ai.jdbc.mcp.model.context;

public record SchemaLintFinding(
        String severity,
        String check,
        String schema,
        String table,
        String column,
        String message,
        String recommendation
) {
}
