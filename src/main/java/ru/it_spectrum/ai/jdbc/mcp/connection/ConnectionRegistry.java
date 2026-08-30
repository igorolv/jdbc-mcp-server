package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connections this server serves, and their lazily created object graphs.
 *
 * <p>Every lookup names its connection — there is no implicit default. Definitions are fixed at
 * startup — the file is re-read only by restarting the server. Contexts are created on first use, one lock per connection, so initialising a slow or unreachable database
 * never blocks a call to another one. A failed initialisation is not cached: the next call retries.
 */
public final class ConnectionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<String, ConnectionDefinition> definitions;
    private final Map<String, Holder> holders;
    private final List<String> orderedNames;

    public ConnectionRegistry(List<ConnectionDefinition> definitions, ConnectionContextFactory factory) {
        Map<String, ConnectionDefinition> byName = new LinkedHashMap<>();
        Map<String, Holder> holders = new LinkedHashMap<>();
        for (ConnectionDefinition definition : definitions) {
            byName.put(definition.name(), definition);
            holders.put(definition.name(), new Holder(definition, factory));
        }
        this.definitions = Map.copyOf(byName);
        // Map.copyOf loses the configured order; keep the ordered view for listings.
        this.orderedNames = List.copyOf(byName.keySet());
        this.holders = Map.copyOf(holders);
    }

    /** A registry over one already-built connection. For tests and embedders. */
    public static ConnectionRegistry fixed(ConnectionContext context) {
        return new ConnectionRegistry(List.of(context.definition()), definition -> context);
    }

    /** Configured connections, in the order they were declared. */
    public List<ConnectionDefinition> definitions() {
        List<ConnectionDefinition> out = new ArrayList<>(orderedNames.size());
        for (String name : orderedNames) {
            out.add(definitions.get(name));
        }
        return List.copyOf(out);
    }

    public Collection<String> names() {
        return orderedNames;
    }

    /** True when this connection's object graph has already been built. */
    public boolean isInitialized(String name) {
        Holder holder = holders.get(name);
        return holder != null && holder.created();
    }

    /**
     * Validates the connection name a tool call named. There is no implicit default: every call
     * says which database it means.
     *
     * @throws IllegalArgumentException when the name is missing or unknown
     */
    public String resolveName(String requested) {
        if (requested == null || requested.isBlank()) {
            throw new IllegalArgumentException("No connection given. Pass 'connection' (available: "
                    + String.join(", ", orderedNames) + ").");
        }
        String name = requested.trim();
        if (!definitions.containsKey(name)) {
            throw new IllegalArgumentException("Unknown connection '" + name + "'. Available: "
                    + String.join(", ", orderedNames));
        }
        return name;
    }

    public ConnectionDefinition definition(String requested) {
        return definitions.get(resolveName(requested));
    }

    /**
     * Resolves and, on first use, builds the object graph for a connection.
     *
     * @throws IllegalArgumentException when the name cannot be resolved or the connection is
     *                                  misconfigured
     */
    public ConnectionContext resolve(String requested) {
        return holders.get(resolveName(requested)).get();
    }

    @Override
    public void close() {
        for (Holder holder : holders.values()) {
            holder.close();
        }
    }

    /**
     * One lazily created context. Synchronising on the holder rather than on the registry keeps
     * connections independent: a database that takes ten seconds to fail only delays its own
     * callers.
     */
    private static final class Holder implements AutoCloseable {

        private final ConnectionDefinition definition;
        private final ConnectionContextFactory factory;
        private volatile ConnectionContext context;

        private Holder(ConnectionDefinition definition, ConnectionContextFactory factory) {
            this.definition = definition;
            this.factory = factory;
        }

        boolean created() {
            return context != null;
        }

        ConnectionContext get() {
            ConnectionContext current = context;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                if (context == null) {
                    context = factory.create(definition);
                }
                return context;
            }
        }

        @Override
        public void close() {
            ConnectionContext current;
            synchronized (this) {
                current = context;
                context = null;
            }
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (RuntimeException e) {
                log.warn("Failed to close connection '{}': {}", definition.name(), e.getMessage());
            }
        }
    }
}
