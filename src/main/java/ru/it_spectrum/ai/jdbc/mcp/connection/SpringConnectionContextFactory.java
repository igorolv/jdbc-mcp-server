package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;

import java.util.function.Supplier;

/**
 * Builds the per-connection object graph as a child {@link ApplicationContext} of the root one.
 *
 * <p>The child scans the same service packages the root context used to scan, so the existing
 * wiring — {@code @Service}, {@code @Autowired}, {@code @Qualifier("usageDataSource")},
 * {@code List<UsageCatalogSource>} — keeps working unchanged; the services simply become
 * singletons of their own connection instead of the process. The connection's own
 * {@code JdbcProperties}, {@code UsageProperties}, {@code StructureSnapshotProperties} and
 * {@code JdbcMcpProperties} are registered as primary beans so they win over the global
 * environment-bound defaults still present in the parent.
 *
 * <p>Every definition is marked lazy: {@link #create} parses bean definitions and returns. No pool,
 * no SQLite file, no database round trip happens until a tool asks for a service — and a database
 * that is down surfaces on that call only, leaving the other connections untouched.
 */
public final class SpringConnectionContextFactory implements ConnectionContextFactory {

    private static final Logger log = LoggerFactory.getLogger(SpringConnectionContextFactory.class);

    private static final String[] SCANNED_PACKAGES = {
            "ru.it_spectrum.ai.jdbc.mcp.dialect",
            "ru.it_spectrum.ai.jdbc.mcp.metadata",
            "ru.it_spectrum.ai.jdbc.mcp.sql",
            "ru.it_spectrum.ai.jdbc.mcp.usage",
    };

    private static final String DATA_SOURCE_BEAN = "dataSource";

    private final ApplicationContext parent;

    public SpringConnectionContextFactory(ApplicationContext parent) {
        this.parent = parent;
    }

    @Override
    public ConnectionContext create(ConnectionDefinition definition) {
        if (!definition.usable()) {
            throw new IllegalArgumentException("Connection '" + definition.name()
                    + "' is misconfigured: " + definition.configError());
        }
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setId("jdbc-mcp-connection-" + definition.name());
        context.setDisplayName("JDBC MCP connection '" + definition.name() + "'");
        context.setParent(parent);
        if (parent.getEnvironment() instanceof ConfigurableEnvironment environment) {
            context.setEnvironment(environment);
        }
        context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor());
        registerPrimary(context, JdbcProperties.class, definition::jdbc);
        registerPrimary(context, JdbcMcpProperties.class, definition::catalog);
        registerPrimary(context, UsageProperties.class, definition::usage);
        registerPrimary(context, StructureSnapshotProperties.class, definition::structureSnapshot);
        context.register(ConnectionScopeConfig.class);
        context.scan(SCANNED_PACKAGES);
        try {
            context.refresh();
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
        log.info("Prepared connection '{}' ({}, url={})", definition.name(), definition.kind(),
                definition.maskedUrl());
        return ConnectionContext.of(definition,
                type -> context.getBean(type),
                () -> context.getBeanFactory().containsSingleton(DATA_SOURCE_BEAN),
                () -> closeQuietly(definition, context));
    }

    /**
     * Registers a per-connection settings bean. It must be primary: the parent context still binds
     * the same types from the environment as global defaults, and both show up as autowire
     * candidates in the child.
     */
    private static <T> void registerPrimary(AnnotationConfigApplicationContext context,
                                            Class<T> type, Supplier<T> value) {
        RootBeanDefinition definition = new RootBeanDefinition(type, value);
        definition.setPrimary(true);
        context.registerBeanDefinition("connection" + type.getSimpleName(), definition);
    }

    private static void closeQuietly(ConnectionDefinition definition, ConfigurableApplicationContext context) {
        try {
            context.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close connection '{}': {}", definition.name(), e.getMessage());
        }
    }
}
