package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.jdbc.mcp.config.DataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;

import javax.sql.DataSource;

/**
 * The two beans a per-connection context needs on top of the scanned services: the detected engine
 * and the read-only JDBC pool.
 *
 * <p>Registered only in per-connection child contexts — never in the root context, which has no
 * single database to point at.
 */
@Configuration(proxyBeanMethods = false)
public class ConnectionScopeConfig {

    @Bean
    public DatabaseKind databaseKind(JdbcProperties properties) {
        return DatabaseKind.fromUrl(properties.url());
    }

    /**
     * Lazy by construction: Hikari is configured with {@code initializationFailTimeout=-1} and
     * {@code minimumIdle} defaulting to 0, so building this bean opens no database connection and
     * an unreachable database fails on the tool call that needs it, not here.
     */
    @Bean(destroyMethod = "close")
    public DataSource dataSource(JdbcProperties properties, DatabaseKind kind,
                                 JdbcMcpProperties catalog) {
        return DataSourceConfig.createDataSource(properties, kind, catalog.resolvedCatalogName());
    }
}
