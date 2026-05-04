package ru.it_spectrum.ai.jdbc.mcp.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Builds a read-only HikariCP-backed {@link DataSource} and exposes the detected {@link DatabaseKind}.
 *
 * <p>Read-only enforcement at the connection layer:
 * <ul>
 *   <li>{@code hikariConfig.setReadOnly(true)} — connections are marked read-only at the pool level;</li>
 *   <li>For PostgreSQL, we append {@code options=-c default_transaction_read_only=on} to the URL
 *       (unless the user already specified {@code options=} themselves) so that every transaction
 *       in the session is read-only on the server side — this blocks even DDL.</li>
 *   <li>For Oracle and SQL Server, the upstream {@code ReadOnlyGuard} is the primary defence;
 *       pooled connections are still marked read-only as a best-effort JDBC hint.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(JdbcProperties.class)
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DatabaseKind databaseKind(JdbcProperties properties) {
        DatabaseKind kind = DatabaseKind.fromUrl(properties.url());
        log.info("Detected database kind: {}", kind);
        return kind;
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(JdbcProperties properties, DatabaseKind kind) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("jdbc-mcp-pool");
        hikari.setJdbcUrl(applyDialectUrlTweaks(properties.url(), kind));
        hikari.setUsername(properties.username());
        hikari.setPassword(properties.password());
        hikari.setReadOnly(true);
        hikari.setAutoCommit(true);
        hikari.setMaximumPoolSize(Math.max(1, properties.poolMaximumSize()));
        hikari.setMinimumIdle(Math.max(0, Math.min(properties.poolMinimumIdle(), properties.poolMaximumSize())));
        hikari.setConnectionTimeout(Math.max(250L, properties.connectionTimeoutMs()));
        hikari.setValidationTimeout(Math.max(250L, properties.validationTimeoutMs()));

        if (properties.defaultSchema() != null && !properties.defaultSchema().isBlank()) {
            hikari.setSchema(properties.defaultSchema());
        }

        log.info("Creating read-only JDBC pool (url={}, user={})",
                maskUrl(hikari.getJdbcUrl()), properties.username());
        return new HikariDataSource(hikari);
    }

    /**
     * For PostgreSQL, force server-side read-only transactions by default via JDBC URL options.
     * Leaves the URL untouched if the user already provided their own {@code options=} parameter
     * or for non-PG engines.
     */
    public static String applyDialectUrlTweaks(String url, DatabaseKind kind) {
        if (kind != DatabaseKind.POSTGRESQL || url == null) {
            return url;
        }
        if (url.toLowerCase().contains("options=")) {
            return url;
        }
        String extra = "options=-c%20default_transaction_read_only%3Don";
        return url + (url.contains("?") ? "&" : "?") + extra;
    }

    private static String maskUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }
}
