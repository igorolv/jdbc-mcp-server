package ru.it_spectrum.ai.jdbc.mcp.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;

import javax.sql.DataSource;

/**
 * Builds a read-only HikariCP-backed {@link DataSource} for one connection.
 *
 * <p>Read-only enforcement at the connection layer:
 * <ul>
 *   <li>{@code hikariConfig.setReadOnly(true)} — connections are marked read-only at the pool level;</li>
 *   <li>The dialect may adjust the URL ({@link SqlDialect#applyUrlTweaks}) — PostgreSQL appends
 *       {@code options=-c default_transaction_read_only=on} so that every transaction in the
 *       session is read-only on the server side, which blocks even DDL.</li>
 *   <li>For Oracle and SQL Server, the upstream {@code ReadOnlyGuard} is the primary defense;
 *       pooled connections are still marked read-only as a best-effort JDBC hint.</li>
 * </ul>
 */
public final class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    private DataSourceConfig() {
    }

    /**
     * @param connectionName names the pool after the connection it serves, so Hikari's own log
     *                       lines say which database they are about
     */
    public static HikariDataSource createDataSource(JdbcProperties properties, DatabaseKind kind,
                                                    String connectionName) {
        HikariConfig hikari = buildHikariConfig(properties, kind);
        if (connectionName != null && !connectionName.isBlank()) {
            hikari.setPoolName("jdbc-mcp-pool-" + connectionName);
        }

        log.info("Creating lazy read-only JDBC pool (connection={}, url={}, user={}, maxSize={}, minIdle={})",
                connectionName, maskUrl(hikari.getJdbcUrl()), properties.username(),
                hikari.getMaximumPoolSize(), hikari.getMinimumIdle());
        return new HikariDataSource(hikari);
    }

    public static HikariConfig buildHikariConfig(JdbcProperties properties, DatabaseKind kind) {
        SqlDialect dialect = SqlDialect.forKind(kind);
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("jdbc-mcp-pool");
        hikari.setJdbcUrl(dialect.applyUrlTweaks(properties.url()));
        hikari.setUsername(properties.username());
        hikari.setPassword(properties.password());
        hikari.setReadOnly(true);
        hikari.setAutoCommit(true);
        hikari.setMaximumPoolSize(Math.max(1, properties.poolMaximumSize()));
        hikari.setMinimumIdle(Math.clamp(properties.poolMinimumIdle(), 0, properties.poolMaximumSize()));
        hikari.setConnectionTimeout(Math.max(250L, properties.connectionTimeoutMs()));
        hikari.setValidationTimeout(Math.max(250L, properties.validationTimeoutMs()));
        hikari.setIdleTimeout(Math.max(10_000L, properties.idleTimeoutMs()));
        hikari.setInitializationFailTimeout(-1);

        dialect.dataSourceProperties().forEach(hikari::addDataSourceProperty);

        if (properties.defaultSchema() != null && !properties.defaultSchema().isBlank()) {
            hikari.setSchema(properties.defaultSchema());
        }

        return hikari;
    }

    /** The URL the pool connects with: the configured one after {@link SqlDialect#applyUrlTweaks}. */
    public static String applyDialectUrlTweaks(String url, DatabaseKind kind) {
        return SqlDialect.forKind(kind).applyUrlTweaks(url);
    }

    /** Masks a {@code password=} parameter so a JDBC URL is safe to log or return to a client. */
    public static String maskUrl(String url) {
        if (url == null) return "";
        return url.replaceAll("(?i)(password=)[^&]*", "$1***");
    }
}
