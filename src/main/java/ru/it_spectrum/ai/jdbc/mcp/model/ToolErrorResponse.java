package ru.it_spectrum.ai.jdbc.mcp.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "ToolErrorResponse response payload.")
public record ToolErrorResponse(
        @Schema(description = "Error.", nullable = true)
        String error,
        @Schema(description = "Kind.", nullable = true)
        String kind,
        @Schema(description = "Missing.", nullable = true)
        String missing,
        @Schema(description = "Name.", nullable = true)
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
