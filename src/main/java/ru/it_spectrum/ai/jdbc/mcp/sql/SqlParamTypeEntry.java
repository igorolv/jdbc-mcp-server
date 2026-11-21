package ru.it_spectrum.ai.jdbc.mcp.sql;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Typed parameter binding override for query/benchmark MCP tools.
 */
public record SqlParamTypeEntry(
        @Schema(description = "Param name (namedParams, without ':') or 1-based '?' index (params)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String param,
        @Schema(description = "ISO formats: date YYYY-MM-DD; timestamp YYYY-MM-DDTHH:mm:ss (no offset); timestamptz with Z or +-HH:MM.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        SqlParamType type
) {
}
