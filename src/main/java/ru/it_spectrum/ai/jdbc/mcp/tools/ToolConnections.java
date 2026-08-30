package ru.it_spectrum.ai.jdbc.mcp.tools;

import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;

/**
 * Shared routing helper for the tool classes. Every tool takes a required leading
 * {@code connection} argument; this turns it into the object graph of one database, reporting a
 * missing or unknown name as an ordinary {@code argument} tool error.
 */
final class ToolConnections {

    /**
     * The description attached to every {@code connection} parameter. Kept to one short sentence:
     * it is repeated across ~50 tool schemas, and the manifest is sent on every connect.
     */
    static final String CONNECTION_PARAM = "Database to run against; a name from listConnections.";

    private ToolConnections() {
    }

    static ConnectionContext resolve(ConnectionRegistry connections, ToolErrors errors, String connection) {
        try {
            return connections.resolve(connection);
        } catch (IllegalArgumentException e) {
            throw errors.argumentException(e);
        } catch (IllegalStateException e) {
            throw errors.unexpectedException(e);
        }
    }
}
