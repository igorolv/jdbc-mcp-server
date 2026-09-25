package ru.it_spectrum.ai.jdbc.mcp.connection;

import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ConnectionRegistry} over a hand-wired service graph, so tests can drive the tool
 * routers without starting a Spring context.
 */
public final class TestConnections {

    private TestConnections() {
    }

    public static ConnectionDefinition definition(String name, JdbcProperties jdbc) {
        return definition(name, jdbc, DatabaseKind.fromUrl(jdbc.url()));
    }

    public static ConnectionDefinition definition(String name, JdbcProperties jdbc, DatabaseKind kind) {
        return new ConnectionDefinition(name, null, jdbc,
                new JdbcMcpProperties(System.getProperty("java.io.tmpdir"), name),
                new UsageProperties(false, List.of(), List.of(), false, false, false, 0),
                new StructureSnapshotProperties(List.of(), 300),
                kind, null);
    }

    /** A registry over already-built contexts. */
    public static ConnectionRegistry registry(ConnectionContext... contexts) {
        Map<String, ConnectionContext> byName = new LinkedHashMap<>();
        List<ConnectionDefinition> definitions = new ArrayList<>();
        for (ConnectionContext context : contexts) {
            byName.put(context.name(), context);
            definitions.add(context.definition());
        }
        return new ConnectionRegistry(definitions, definition -> byName.get(definition.name()));
    }

    /**
     * A context over the given services, keyed by their runtime class. The engine is the one of the
     * {@link SqlDialect} among them, else detected from the URL.
     */
    public static ConnectionContext context(String name, JdbcProperties jdbc, Object... services) {
        Map<Class<?>, Object> beans = new IdentityHashMap<>();
        DatabaseKind kind = null;
        for (Object service : services) {
            beans.put(service.getClass(), service);
            if (service instanceof SqlDialect dialect) {
                kind = dialect.kind();
            }
        }
        return ConnectionContext.ofBeans(
                definition(name, jdbc, kind != null ? kind : DatabaseKind.fromUrl(jdbc.url())), beans);
    }

    /** A single-connection registry serving the given services, keyed by their runtime class. */
    public static ConnectionRegistry registry(String name, JdbcProperties jdbc, Object... services) {
        return ConnectionRegistry.fixed(context(name, jdbc, services));
    }
}
