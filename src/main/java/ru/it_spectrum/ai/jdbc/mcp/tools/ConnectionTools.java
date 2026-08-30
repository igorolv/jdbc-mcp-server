package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.model.connection.ConnectionInfo;
import ru.it_spectrum.ai.jdbc.mcp.model.connection.ListConnectionsResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovery for the {@code connection} argument every other tool requires. This is the first call
 * to make against an unfamiliar installation: it answers "which databases does this server
 * serve?".
 *
 * <p>Reads configuration and the local filesystem only — it never opens a database connection, so
 * it stays useful even when some of the configured databases are down.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "connections", havingValue = "true", matchIfMissing = true)
public class ConnectionTools {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTools.class);

    private final ConnectionRegistry connections;

    public ConnectionTools(ConnectionRegistry connections) {
        this.connections = connections;
    }

    @McpTool(
            description = "Discover the databases served here and the valid name to pass as 'connection'. Call " +
                    "when the target connection is not already established; returns purpose, engine, default schema " +
                    "and local-catalog availability without opening a database connection.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListConnectionsResult listConnections() {
        log.info("Tool call: listConnections");
        long start = System.nanoTime();
        List<ConnectionInfo> infos = new ArrayList<>();
        for (ConnectionDefinition definition : connections.definitions()) {
            infos.add(new ConnectionInfo(
                    definition.name(),
                    definition.description(),
                    displayKind(definition.kind()),
                    blankToNull(definition.jdbc().defaultSchema()),
                    definition.hasLocalSnapshot(),
                    connections.isInitialized(definition.name()),
                    definition.configError()));
        }
        ToolLogger.completed(log, "listConnections", start);
        return new ListConnectionsResult(List.copyOf(infos));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String displayKind(DatabaseKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case POSTGRESQL -> "PostgreSQL";
            case ORACLE -> "Oracle";
            case MSSQL -> "SQL Server";
        };
    }
}
