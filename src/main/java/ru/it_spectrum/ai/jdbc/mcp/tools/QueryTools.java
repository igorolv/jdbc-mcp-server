package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.format.OutputFormat;
import ru.it_spectrum.ai.jdbc.mcp.format.ResultFormatter;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlNotAllowedException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * MCP tools for executing SELECT queries, getting execution plans and validating SQL.
 * All tools are strictly read-only — write statements are rejected by {@link ReadOnlyGuard}
 * before reaching the database.
 */
@Service
public class QueryTools {

    private final SqlExecutor executor;
    private final SqlDialect dialect;
    private final JdbcProperties properties;
    private final ReadOnlyGuard guard;

    public QueryTools(SqlExecutor executor, SqlDialect dialect,
                      JdbcProperties properties, ReadOnlyGuard guard) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.guard = guard;
    }

    @McpTool(description = "Execute a read-only SQL SELECT / WITH / EXPLAIN statement and return the result. " +
            "Only pure read statements are allowed — write operations (INSERT, UPDATE, DELETE, DDL, etc.) " +
            "are rejected before being sent to the database. " +
            "Use '?' placeholders for parameters and pass values in 'params'. " +
            "Output format: 'json' (default), 'markdown', or 'csv'. " +
            "Results are truncated to 'limit' rows (default JDBC_MAX_ROWS) with a 'truncated' marker.")
    public String executeQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order (optional)", required = false) List<Object> params,
            @McpToolParam(description = "Max rows to return (optional, default JDBC_MAX_ROWS)", required = false) Integer limit,
            @McpToolParam(description = "Per-query timeout in seconds (optional, default JDBC_QUERY_TIMEOUT_SECONDS)", required = false) Integer timeoutSeconds,
            @McpToolParam(description = "Output format: json (default), markdown, csv", required = false) String format
    ) {
        try {
            OutputFormat fmt = OutputFormat.parse(format);
            QueryResult result = executor.query(sql, params, limit, timeoutSeconds);
            return ResultFormatter.format(result, fmt);
        } catch (SqlNotAllowedException e) {
            return "Rejected: " + e.getMessage();
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Return the execution plan for a SQL SELECT / WITH statement. " +
            "PostgreSQL: uses EXPLAIN (FORMAT TEXT); with analyze=true runs EXPLAIN ANALYZE (note: this actually executes the query!). " +
            "Oracle: uses EXPLAIN PLAN + DBMS_XPLAN.DISPLAY; analyze flag is ignored (Oracle returns a static plan). " +
            "The statement is still read-only-validated before execution.")
    public String explainQuery(
            @McpToolParam(description = "SQL statement to explain") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders (optional)", required = false) List<Object> params,
            @McpToolParam(description = "PostgreSQL only: collect actual run-time stats via EXPLAIN ANALYZE. " +
                    "Default false. Setting this to true causes the query to actually run!", required = false) Boolean analyze
    ) {
        try {
            guard.check(sql);
            boolean doAnalyze = analyze != null && analyze;
            String explainSql = dialect.buildExplain(sql, doAnalyze);
            String displaySql = dialect.explainDisplayQuery();

            return executor.withConnection(conn -> {
                // Two-step plan flow for Oracle (EXPLAIN PLAN FOR ... ; SELECT * FROM DBMS_XPLAN.DISPLAY).
                // For PostgreSQL displaySql is null — the EXPLAIN itself yields the plan rows.
                if (displaySql != null) {
                    runUpdate(conn, explainSql, params);
                    QueryResult planRows = queryNoParams(conn, displaySql);
                    return rowsAsText(planRows);
                }
                QueryResult planRows = queryWithParams(conn, explainSql, params);
                return rowsAsText(planRows);
            });
        } catch (SqlNotAllowedException e) {
            return "Rejected: " + e.getMessage();
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        }
    }

    @McpTool(description = "Validate a SQL statement without executing it: checks the read-only guard " +
            "and prepares it with the driver (which verifies syntax and referenced objects). " +
            "Useful to let an LLM self-correct before running a real query.")
    public String validateQuery(
            @McpToolParam(description = "SQL statement to validate") String sql
    ) {
        try {
            guard.check(sql);
        } catch (SqlNotAllowedException e) {
            return "INVALID (guard): " + e.getMessage();
        }
        try {
            return executor.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int paramCount = ps.getParameterMetaData().getParameterCount();
                    int colCount = 0;
                    try {
                        colCount = ps.getMetaData() != null ? ps.getMetaData().getColumnCount() : 0;
                    } catch (SQLException ignore) {
                        // some drivers can't describe a prepared SELECT without execution
                    }
                    return "VALID. Parameters: " + paramCount + ", columns: " + colCount;
                }
            });
        } catch (SQLException e) {
            return "INVALID (driver): " + e.getMessage();
        }
    }

    // ---------------- helpers ----------------

    private QueryResult queryWithParams(Connection conn, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (properties.queryTimeoutSeconds() > 0) ps.setQueryTimeout(properties.queryTimeoutSeconds());
            bind(ps, params);
            try (var rs = ps.executeQuery()) {
                return readAll(rs);
            }
        }
    }

    private QueryResult queryNoParams(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (properties.queryTimeoutSeconds() > 0) ps.setQueryTimeout(properties.queryTimeoutSeconds());
            try (var rs = ps.executeQuery()) {
                return readAll(rs);
            }
        }
    }

    private void runUpdate(Connection conn, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (properties.queryTimeoutSeconds() > 0) ps.setQueryTimeout(properties.queryTimeoutSeconds());
            bind(ps, params);
            ps.execute();
        }
    }

    private void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) return;
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private QueryResult readAll(java.sql.ResultSet rs) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        java.util.List<String> cols = new java.util.ArrayList<>(n);
        java.util.List<String> types = new java.util.ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            cols.add(md.getColumnLabel(i));
            types.add(md.getColumnTypeName(i));
        }
        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
        while (rs.next()) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int i = 1; i <= n; i++) row.put(cols.get(i - 1), rs.getObject(i));
            rows.add(row);
        }
        return new QueryResult(cols, types, rows, false, rows.size());
    }

    /** Joins all first-column values into one text block — the canonical form for query plans. */
    private String rowsAsText(QueryResult r) {
        if (r.rows().isEmpty()) return "(empty plan)";
        String key = r.columns().isEmpty() ? null : r.columns().get(0);
        StringBuilder sb = new StringBuilder();
        for (var row : r.rows()) {
            Object v = key == null ? row.values().iterator().next() : row.get(key);
            if (v != null) sb.append(v).append('\n');
        }
        return sb.toString();
    }

    // Kept to prevent IDE "unused" warnings; the guard instance is injected so Spring wires it.
    @SuppressWarnings("unused")
    private List<Object> noParams() {
        return Collections.emptyList();
    }
}
