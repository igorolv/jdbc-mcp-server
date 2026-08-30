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
 * <p>The root context knows only where the server keeps its data and where the connections file is.
 * Everything database-facing — pools, dialects, services, per-connection settings — lives in the
 * child contexts the registry creates on demand.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JdbcMcpProperties.class)
public class ConnectionsConfig {

    @Bean
    public ConnectionContextFactory connectionContextFactory(ApplicationContext applicationContext) {
        return new SpringConnectionContextFactory(applicationContext);
    }

    @Bean(destroyMethod = "close")
    public ConnectionRegistry connectionRegistry(JdbcMcpProperties serverProperties,
                                                 ObjectMapper mapper,
                                                 ConnectionContextFactory factory) {
        return new ConnectionRegistry(
                ConnectionsLoader.load(serverProperties, mapper, System::getenv), factory);
    }
}
