package ru.it_spectrum.ai.jdbc.mcp.connection;

/**
 * Builds the object graph for one connection. Split from {@link ConnectionRegistry} so the registry
 * can be driven by a hand-wired graph in tests as easily as by the Spring child contexts
 * {@link SpringConnectionContextFactory} creates in production.
 */
@FunctionalInterface
public interface ConnectionContextFactory {

    ConnectionContext create(ConnectionDefinition definition);
}
