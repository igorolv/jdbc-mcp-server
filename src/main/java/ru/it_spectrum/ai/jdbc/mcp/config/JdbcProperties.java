package ru.it_spectrum.ai.jdbc.mcp.config;

/**
 * Connection configuration for the read-only JDBC data source.
 *
 * <p>One instance per connection, built by {@link ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionsLoader}
 * from a {@code connections.json} entry; {@link #DEFAULTS} fills in whatever the entry leaves out.
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
 * @param poolMaximumSize     Hikari maximum pool size
 * @param poolMinimumIdle     Hikari minimum idle connections
 * @param connectionTimeoutMs Hikari connection checkout timeout in milliseconds
 * @param validationTimeoutMs Hikari validation timeout in milliseconds
 * @param idleTimeoutMs       Hikari idle connection timeout in milliseconds
 */
public record JdbcProperties(
        String url,
        String username,
        String password,
        String defaultSchema,
        int queryTimeoutSeconds,
        int maxRows,
        int fetchSize,
        String readonlyGuard,
        int poolMaximumSize,
        int poolMinimumIdle,
        int connectionTimeoutMs,
        int validationTimeoutMs,
        int idleTimeoutMs
) {

    /**
     * Values used for every field a {@code connections.json} entry does not set. The connection
     * fields ({@code url}, {@code username}, {@code password}, {@code defaultSchema}) have no
     * default: they are always per-connection.
     */
    public static final JdbcProperties DEFAULTS = new JdbcProperties(
            null, null, null, null, 30, 1000, 500, "strict", 40, 0, 10_000, 5_000, 60_000);

    public boolean guardEnabled() {
        return readonlyGuard == null || !"off".equalsIgnoreCase(readonlyGuard.trim());
    }
}
