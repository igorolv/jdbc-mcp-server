package ru.it_spectrum.ai.jdbc.mcp.model.connection;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Databases configured on this server and which one tool calls hit by default.")
public record ListConnectionsResult(
        @Schema(description = "Configured connections, in configuration order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<ConnectionInfo> connections,
        @Schema(description = "Connection used when a tool call omits 'connection'; absent when a call must name one.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String defaultConnection
) {
}
