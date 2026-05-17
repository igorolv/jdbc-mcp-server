package ru.it_spectrum.ai.jdbc.mcp.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Standard error object returned by a tool when an argument, SQL, driver, lookup, or unexpected failure prevents a normal result.")
public record ToolErrorResponse(
        @Schema(description = "Human-readable error message explaining why the requested operation failed.", nullable = true)
        String error,
        @Schema(description = "Machine-readable error or category code for branching on the failure type.", nullable = true)
        String kind,
        @Schema(description = "Name of the missing argument or database object when the failure is a not-found condition.", nullable = true)
        String missing,
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
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
