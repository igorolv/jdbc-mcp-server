package ru.it_spectrum.ai.jdbc.mcp.connection;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;

import java.util.IdentityHashMap;
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
        return new ConnectionDefinition(name, null, jdbc,
                new JdbcMcpProperties(System.getProperty("java.io.tmpdir"), name),
                new UsageProperties(false, List.of(), List.of(), false, false, false, 0),
                new StructureSnapshotProperties(List.of(), 300),
                DatabaseKind.fromUrl(jdbc.url()), null);
    }

    /** A single-connection registry serving the given services, keyed by their runtime class. */
    public static ConnectionRegistry registry(String name, JdbcProperties jdbc, Object... services) {
        Map<Class<?>, Object> beans = new IdentityHashMap<>();
        for (Object service : services) {
            beans.put(service.getClass(), service);
        }
        return ConnectionRegistry.fixed(ConnectionContext.ofBeans(definition(name, jdbc), beans));
    }
}
