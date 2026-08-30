package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContextFactory;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.jdbc.mcp.connection.SpringConnectionContextFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Root-context wiring for the connection registry.
 *
 * <p>The four properties records bound here are the global defaults: with no connections file they
 * describe the single environment-configured database, and with one they fill in whatever an entry
 * does not override. Everything database-facing lives in the per-connection child contexts the
 * registry creates on demand.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JdbcProperties.class, JdbcMcpProperties.class,
        UsageProperties.class, StructureSnapshotProperties.class})
public class ConnectionsConfig {

    @Bean
    public ConnectionContextFactory connectionContextFactory(ApplicationContext applicationContext) {
        return new SpringConnectionContextFactory(applicationContext);
    }

    @Bean(destroyMethod = "close")
    public ConnectionRegistry connectionRegistry(JdbcProperties jdbcProperties,
                                                 JdbcMcpProperties jdbcMcpProperties,
                                                 UsageProperties usageProperties,
                                                 StructureSnapshotProperties structureSnapshotProperties,
                                                 ObjectMapper mapper,
                                                 ConnectionContextFactory factory) {
        ConnectionsLoader.Loaded loaded = ConnectionsLoader.load(
                jdbcMcpProperties.resolvedConnectionsFile(),
                jdbcProperties, jdbcMcpProperties, usageProperties, structureSnapshotProperties,
                mapper, System::getenv);
        return new ConnectionRegistry(loaded.definitions(), loaded.defaultConnection(), factory);
    }
}
