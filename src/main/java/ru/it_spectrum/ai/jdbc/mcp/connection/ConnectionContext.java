package ru.it_spectrum.ai.jdbc.mcp.connection;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser;
import ru.it_spectrum.ai.jdbc.mcp.sql.BenchmarkService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryLineageService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryLintService;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.CatalogStorageService;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The object graph serving one named connection: pool, dialect, metadata, catalog and query
 * services. Tool classes stay singletons in the root context and route each call through here.
 *
 * <p>Everything behind {@link #bean(Class)} is created on first use, so holding a context costs
 * nothing until a tool actually asks for a service — that is what keeps a call to one database from
 * opening pools for the other fourteen.
 */
public final class ConnectionContext implements AutoCloseable {

    private final ConnectionDefinition definition;
    private final Function<Class<?>, Object> lookup;
    private final BooleanSupplier poolInitialized;
    private final Runnable closer;

    private ConnectionContext(ConnectionDefinition definition, Function<Class<?>, Object> lookup,
                              BooleanSupplier poolInitialized, Runnable closer) {
        this.definition = definition;
        this.lookup = lookup;
        this.poolInitialized = poolInitialized;
        this.closer = closer;
    }

    /**
     * Wraps a lazily-initialised per-connection Spring context. {@code poolInitialized} tells
     * whether the JDBC pool has been built yet, without building it.
     */
    public static ConnectionContext of(ConnectionDefinition definition,
                                       Function<Class<?>, Object> lookup,
                                       BooleanSupplier poolInitialized,
                                       Runnable closer) {
        return new ConnectionContext(definition, lookup, poolInitialized, closer);
    }

    /**
     * A context over an already-assembled set of services. Used by tests and by embedders that wire
     * the graph themselves instead of letting Spring do it.
     */
    public static ConnectionContext ofBeans(ConnectionDefinition definition, Map<Class<?>, Object> beans) {
        Map<Class<?>, Object> copy = new IdentityHashMap<>(beans);
        return new ConnectionContext(definition, type -> {
            Object exact = copy.get(type);
            if (exact != null) {
                return exact;
            }
            for (Object candidate : copy.values()) {
                if (type.isInstance(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("No " + type.getSimpleName() + " configured for connection '"
                    + definition.name() + "'");
        }, () -> true, () -> {
        });
    }

    public String name() {
        return definition.name();
    }

    public ConnectionDefinition definition() {
        return definition;
    }

    public DatabaseKind kind() {
        return definition.kind();
    }

    /** True once the JDBC pool for this connection has been built. Does not build it. */
    public boolean poolInitialized() {
        return poolInitialized.getAsBoolean();
    }

    public <T> T bean(Class<T> type) {
        return type.cast(lookup.apply(type));
    }

    /** Effective JDBC settings for this connection (timeouts, row caps, guard mode). */
    public JdbcProperties jdbcProperties() {
        return definition.jdbc();
    }

    public SqlExecutor executor() {
        return bean(SqlExecutor.class);
    }

    public SqlDialect dialect() {
        return bean(SqlDialect.class);
    }

    public ReadOnlyGuard guard() {
        return bean(ReadOnlyGuard.class);
    }

    public PlanParser planParser() {
        return bean(PlanParser.class);
    }

    public MetadataService metadata() {
        return bean(MetadataService.class);
    }

    public StatsService stats() {
        return bean(StatsService.class);
    }

    public DistributionService distribution() {
        return bean(DistributionService.class);
    }

    public SchemaContextService schemaContext() {
        return bean(SchemaContextService.class);
    }

    public BenchmarkService benchmarks() {
        return bean(BenchmarkService.class);
    }

    public QueryAnalysisService analysis() {
        return bean(QueryAnalysisService.class);
    }

    public QueryLineageService lineage() {
        return bean(QueryLineageService.class);
    }

    public QueryLintService lint() {
        return bean(QueryLintService.class);
    }

    public UsageCatalogService usageCatalog() {
        return bean(UsageCatalogService.class);
    }

    public CatalogStorageService catalogStorage() {
        return bean(CatalogStorageService.class);
    }

    public StructureSnapshotStore snapshotStore() {
        return bean(StructureSnapshotStore.class);
    }

    /** Local-catalog settings for this connection: {@code <data-dir>/<name>/}. */
    public JdbcMcpProperties catalogProperties() {
        return definition.catalog();
    }

    @Override
    public void close() {
        closer.run();
    }
}
