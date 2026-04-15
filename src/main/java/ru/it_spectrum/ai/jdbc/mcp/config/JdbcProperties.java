package ru.it_spectrum.ai.jdbc.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection configuration for the read-only JDBC data source.
 *
 * <p>Values come from environment variables via {@code application.yml}
 * ({@code JDBC_URL}, {@code JDBC_USERNAME}, {@code JDBC_PASSWORD}, etc.).
 *
 * @param url                 JDBC URL, e.g. {@code jdbc:postgresql://host:5432/db}
 *                            or {@code jdbc:oracle:thin:@//host:1521/service}
 * @param username            database user (must have SELECT-only privileges for maximum safety)
 * @param password            database password
 * @param defaultSchema       default schema used when metadata tools are called without explicit schema
 * @param queryTimeoutSeconds per-statement timeout in seconds (0 disables)
 * @param maxRows             max rows returned by a single query (truncation marker is added if exceeded)
 * @param fetchSize           JDBC fetch size hint
 * @param readonlyGuard       strict (default) — reject anything that does not start with SELECT/WITH/EXPLAIN;
 *                            off — no client-side guard (drivers' readOnly flag still applied)
 */
@ConfigurationProperties(prefix = "jdbc")
public record JdbcProperties(
        String url,
        String username,
        String password,
        String defaultSchema,
        int queryTimeoutSeconds,
        int maxRows,
        int fetchSize,
        String readonlyGuard
) {
    public boolean guardEnabled() {
        return readonlyGuard == null || !"off".equalsIgnoreCase(readonlyGuard.trim());
    }
}
