package ru.it_spectrum.ai.jdbc.mcp.model;

public record ToolErrorResponse(
        String error,
        String kind,
        String missing,
        String name
) {
    public static ToolErrorResponse of(String kind, String error) {
        return new ToolErrorResponse(error, kind, null, null);
    }

    public static ToolErrorResponse notFound(String missing, String name) {
        return new ToolErrorResponse(
                missing + " '" + name + "' not found",
                "not_found",
                missing,
                name
        );
    }
}
