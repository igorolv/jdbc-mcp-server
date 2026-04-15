package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes read-only SELECT/WITH/EXPLAIN statements against the configured DataSource.
 *
 * <p>Responsibilities:
 * <ul>
 *     <li>Run the {@link ReadOnlyGuard} check;</li>
 *     <li>Acquire a connection and apply dialect-specific read-only hardening;</li>
 *     <li>Enforce per-statement timeout and {@code maxRows} limits;</li>
 *     <li>Bind positional parameters (for {@code PreparedStatement});</li>
 *     <li>Assemble a {@link QueryResult} preserving column order.</li>
 * </ul>
 */
@Component
public class SqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutor.class);

    private final DataSource dataSource;
    private final SqlDialect dialect;
    private final JdbcProperties properties;
    private final ReadOnlyGuard guard;

    public SqlExecutor(DataSource dataSource, SqlDialect dialect,
                       JdbcProperties properties, ReadOnlyGuard guard) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.properties = properties;
        this.guard = guard;
    }

    /**
     * Runs a user query. Applies the guard, enforces limits, returns a structured {@link QueryResult}.
     *
     * @param sql            the SELECT/WITH/EXPLAIN statement
     * @param params         positional parameters for {@code ?} placeholders, may be null or empty
     * @param limit          max rows to return (null → use configured default)
     * @param timeoutSeconds per-query timeout override (null → configured default)
     */
    public QueryResult query(String sql, List<Object> params, Integer limit, Integer timeoutSeconds)
            throws SQLException {
        guard.check(sql);
        int effectiveLimit = limit != null && limit > 0 ? limit : properties.maxRows();
        int effectiveTimeout = timeoutSeconds != null && timeoutSeconds >= 0
                ? timeoutSeconds : properties.queryTimeoutSeconds();

        try (Connection conn = dataSource.getConnection()) {
            dialect.prepareReadOnly(conn);
            // We do not rewrite the query with LIMIT here: the caller may legitimately want all rows
            // up to maxRows. Truncation is enforced via ResultSet iteration + maxRows+1 probe.
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                applyLimits(ps, effectiveTimeout, effectiveLimit);
                bindParameters(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return readResult(rs, effectiveLimit);
                }
            }
        }
    }

    /**
     * Runs a raw internal query against the primary DataSource without going through the guard.
     * Reserved for metadata/dialect queries constructed by {@link SqlDialect} implementations,
     * which are static and safe by construction.
     */
    public <T> T withConnection(ConnectionHandler<T> handler) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            dialect.prepareReadOnly(conn);
            return handler.handle(conn);
        }
    }

    /**
     * Executes an arbitrary parameterised SELECT, bypassing the guard. Used for metadata queries.
     */
    public QueryResult queryInternal(String sql, List<Object> params, int limit) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            dialect.prepareReadOnly(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                applyLimits(ps, properties.queryTimeoutSeconds(), limit);
                bindParameters(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return readResult(rs, limit);
                }
            }
        }
    }

    private void applyLimits(Statement st, int timeoutSec, int maxRows) throws SQLException {
        if (timeoutSec > 0) {
            st.setQueryTimeout(timeoutSec);
        }
        // maxRows+1 probe strategy: ask for one extra row so we can report truncation.
        if (maxRows > 0) {
            try {
                st.setMaxRows(maxRows + 1);
            } catch (SQLException ignore) {
                // Some drivers do not support setMaxRows — we still apply the limit in readResult.
            }
        }
        if (properties.fetchSize() > 0) {
            try {
                st.setFetchSize(properties.fetchSize());
            } catch (SQLException ignore) {
                // best-effort
            }
        }
    }

    private void bindParameters(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null || params.isEmpty()) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v == null) {
                ps.setNull(i + 1, Types.VARCHAR);
            } else {
                ps.setObject(i + 1, v);
            }
        }
    }

    private QueryResult readResult(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        List<String> columnTypes = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            String label = md.getColumnLabel(i);
            columns.add(label != null && !label.isBlank() ? label : md.getColumnName(i));
            columnTypes.add(md.getColumnTypeName(i));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (maxRows > 0 && rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>(colCount * 2);
            for (int i = 1; i <= colCount; i++) {
                row.put(columns.get(i - 1), convertValue(rs, i));
            }
            rows.add(row);
        }
        if (truncated) {
            log.debug("Query result truncated at {} rows", maxRows);
        }
        return new QueryResult(columns, columnTypes, rows, truncated, rows.size());
    }

    private Object convertValue(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null) return null;
        // Normalize a few types that are awkward for JSON / text output:
        if (v instanceof java.sql.Clob clob) {
            long len = clob.length();
            return clob.getSubString(1, (int) Math.min(len, 1_000_000L));
        }
        if (v instanceof java.sql.Blob blob) {
            return "<BLOB " + blob.length() + " bytes>";
        }
        if (v instanceof java.sql.SQLXML xml) {
            return xml.getString();
        }
        return v;
    }

    @FunctionalInterface
    public interface ConnectionHandler<T> {
        T handle(Connection connection) throws SQLException;
    }
}
