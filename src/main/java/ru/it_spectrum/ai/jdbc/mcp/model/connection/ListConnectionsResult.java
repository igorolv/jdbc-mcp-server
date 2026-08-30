package ru.it_spectrum.ai.jdbc.mcp.model.connection;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Databases configured on this server; every other tool names one of them.")
public record ListConnectionsResult(
        @Schema(description = "Configured connections, in configuration order.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<ConnectionInfo> connections
) {
}
