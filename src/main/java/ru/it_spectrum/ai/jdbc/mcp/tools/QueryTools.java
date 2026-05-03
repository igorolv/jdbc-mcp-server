package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.format.OutputFormat;
import ru.it_spectrum.ai.jdbc.mcp.format.ResultFormatter;
import ru.it_spectrum.ai.jdbc.mcp.plan.ParsedPlan;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanAnalyzer;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser;
import ru.it_spectrum.ai.jdbc.mcp.sql.NamedParameterRewriter;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParameterBindingResolver;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlNotAllowedException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for executing SELECT queries, getting execution plans and validating SQL.
 * All tools are strictly read-only — write statements are rejected by {@link ReadOnlyGuard}
 * before reaching the database.
 */
@Service
public class QueryTools {

    private static final String BINDING_RULES =
            "Binding rules: if SQL has no placeholders, omit both 'params' and 'namedParams'. " +
            "If SQL contains '?', pass values in 'params' only, in placeholder order. " +
            "If SQL contains named placeholders in the form ':paramName' such as ':userId' or ':status', " +
            "pass values in 'namedParams' only. " +
            "Never mix '?' and named placeholders in the same SQL statement, and never pass both argument styles. ";

    private static final String BINDING_EXAMPLES =
            "Examples: positional -> sql='SELECT * FROM orders WHERE customer_id = ? AND status = ?', " +
            "params=[123, 'PAID']; named -> sql='SELECT * FROM orders WHERE customer_id = :customerId " +
            "AND status = :status', namedParams={customerId: 123, status: 'PAID'}. ";

    private final SqlExecutor executor;
    private final SqlDialect dialect;
    private final JdbcProperties properties;
    private final ReadOnlyGuard guard;
    private final PlanParser planParser;

    public QueryTools(SqlExecutor executor, SqlDialect dialect,
                      JdbcProperties properties, ReadOnlyGuard guard,
                      PlanParser planParser) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.guard = guard;
        this.planParser = planParser;
    }

    @McpTool(description = "Execute a read-only SQL SELECT / WITH / EXPLAIN statement and return the result. " +
            "Only pure read statements are allowed — write operations (INSERT, UPDATE, DELETE, DDL, etc.) " +
            "are rejected before being sent to the database. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Output format: 'json' (default), 'markdown', or 'csv'. " +
            "Results are truncated to 'limit' rows (default JDBC_MAX_ROWS) with a 'truncated' marker.")
    public String executeQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order. Required when SQL contains '?'.", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders. Required when SQL contains ':name'.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows to return (optional, default JDBC_MAX_ROWS)", required = false) Integer limit,
            @McpToolParam(description = "Per-query timeout in seconds (optional, default JDBC_QUERY_TIMEOUT_SECONDS)", required = false) Integer timeoutSeconds,
            @McpToolParam(description = "Output format: json (default), markdown, csv", required = false) String format
    ) {
        try {
            String normalizedSql = normalizeSql(sql);
            OutputFormat fmt = OutputFormat.parse(format);
            QueryResult result = query(normalizedSql, params, namedParams, limit, timeoutSeconds);
            return ResultFormatter.format(result, fmt);
        } catch (SqlNotAllowedException e) {
            return ToolErrors.rejected(e);
        } catch (SQLException e) {
            return ToolErrors.sql(e);
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        }
    }

    @McpTool(description = "Return the execution plan for a SQL SELECT / WITH statement. " +
            "PostgreSQL: uses EXPLAIN (FORMAT TEXT); with analyze=true runs EXPLAIN ANALYZE (note: this actually executes the query!). " +
            "Oracle: uses EXPLAIN PLAN + DBMS_XPLAN.DISPLAY; analyze flag is ignored (Oracle returns a static plan). " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "The statement is still read-only-validated before execution.")
    public String explainQuery(
            @McpToolParam(description = "SQL statement to explain") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order. Required when SQL contains '?'.", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders. Required when SQL contains ':name'.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "PostgreSQL only: collect actual run-time stats via EXPLAIN ANALYZE. " +
                    "Default false. Setting this to true causes the query to actually run!", required = false) Boolean analyze
    ) {
        try {
            String normalizedSql = normalizeSql(sql);
            guard.check(normalizedSql);
            boolean doAnalyze = analyze != null && analyze;
            String explainSql = dialect.buildExplain(normalizedSql, doAnalyze);
            String displaySql = dialect.explainDisplayQuery();

            return executor.withConnection(conn -> {
                SqlParameterBindingResolver.Binding binding = resolveBinding(normalizedSql, params, namedParams);
                String preparedExplainSql;
                List<Object> preparedParams;
                if (binding.namedParams() != null) {
                    NamedParameterRewriter.PreparedSql prep =
                            NamedParameterRewriter.rewrite(explainSql, binding.namedParams());
                    preparedExplainSql = prep.sql();
                    preparedParams = prep.params();
                } else {
                    preparedExplainSql = explainSql;
                    preparedParams = params != null ? params : Collections.emptyList();
                }

                // Two-step plan flow for Oracle (EXPLAIN PLAN FOR ... ; SELECT * FROM DBMS_XPLAN.DISPLAY).
                // For PostgreSQL displaySql is null — the EXPLAIN itself yields the plan rows.
                if (displaySql != null) {
                    runUpdate(conn, preparedExplainSql, preparedParams);
                    QueryResult planRows = queryNoParams(conn, displaySql);
                    return rowsAsText(planRows);
                }
                QueryResult planRows = queryWithParams(conn, preparedExplainSql, preparedParams);
                return rowsAsText(planRows);
            }, displaySql == null);
        } catch (SqlNotAllowedException e) {
            return ToolErrors.rejected(e);
        } catch (SQLException e) {
            return ToolErrors.sql(e);
        } catch (Exception e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Run a structured EXPLAIN and return a compact, LLM-friendly summary of the " +
            "execution plan instead of the full dump: top expensive nodes, full table scans on large " +
            "relations, estimation errors (planner vs. reality — requires analyze=true on PG), risky " +
            "nested loops with large outer inputs, and disk-sort spills. " +
            "PostgreSQL: uses EXPLAIN (FORMAT JSON); analyze=true switches to EXPLAIN ANALYZE (the query is executed!). " +
            "Oracle: uses EXPLAIN PLAN + PLAN_TABLE; analyze flag is ignored (static plan only, no actual rows / times). " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Use this to decide whether to add an index, refresh statistics, or rewrite a JOIN.")
    public String analyzePlan(
            @McpToolParam(description = "SQL statement to analyze") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order. Required when SQL contains '?'.", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders. Required when SQL contains ':name'.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "PostgreSQL only: collect actual row counts / timings via EXPLAIN ANALYZE. " +
                    "Default false. Setting this to true causes the query to actually run!", required = false) Boolean analyze
    ) {
        try {
            String normalizedSql = normalizeSql(sql);
            guard.check(normalizedSql);
            boolean doAnalyze = analyze != null && analyze;
            String explainSql = dialect.buildStructuredExplain(normalizedSql, doAnalyze);
            String displaySql = dialect.structuredPlanQuery();

            ParsedPlan parsed = executor.withConnection(conn -> {
                SqlParameterBindingResolver.Binding binding = resolveBinding(normalizedSql, params, namedParams);
                String preparedExplainSql;
                List<Object> preparedParams;
                if (binding.namedParams() != null) {
                    NamedParameterRewriter.PreparedSql prep =
                            NamedParameterRewriter.rewrite(explainSql, binding.namedParams());
                    preparedExplainSql = prep.sql();
                    preparedParams = prep.params();
                } else {
                    preparedExplainSql = explainSql;
                    preparedParams = params != null ? params : Collections.emptyList();
                }

                QueryResult planRows;
                if (displaySql != null) {
                    // Oracle: populate PLAN_TABLE, then read the typed columns back.
                    runUpdate(conn, preparedExplainSql, preparedParams);
                    planRows = queryNoParams(conn, displaySql);
                } else {
                    // PostgreSQL: FORMAT JSON EXPLAIN returns one row with the plan document.
                    planRows = queryWithParams(conn, preparedExplainSql, preparedParams);
                }
                return planParser.parse(planRows, doAnalyze);
            }, displaySql == null);
            return JsonWriter.write(PlanAnalyzer.summarize(parsed));
        } catch (SqlNotAllowedException e) {
            return ToolErrors.rejected(e);
        } catch (SQLException e) {
            return ToolErrors.sql(e);
        } catch (IllegalArgumentException e) {
            return ToolErrors.planParse(e);
        } catch (Exception e) {
            return ToolErrors.unexpected(e);
        }
    }

    @McpTool(description = "Validate a SQL statement without executing it: checks the read-only guard " +
            "and prepares it with the driver (which verifies syntax and referenced objects). " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Useful to let an LLM self-correct before running a real query.")
    public String validateQuery(
            @McpToolParam(description = "SQL statement to validate") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order. Required when SQL contains '?'.", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders. Required when SQL contains ':name'.", required = false) Map<String, Object> namedParams
    ) {
        String normalizedSql = normalizeSql(sql);
        try {
            guard.check(normalizedSql);
        } catch (SqlNotAllowedException e) {
            return validationFailure("guard", e.getMessage());
        }
        try {
            SqlParameterBindingResolver.Binding binding = resolveBinding(normalizedSql, params, namedParams);
            String preparedSql = normalizedSql;
            List<Object> preparedParams = binding.params();
            if (binding.namedParams() != null) {
                NamedParameterRewriter.PreparedSql prepared =
                        NamedParameterRewriter.rewrite(normalizedSql, binding.namedParams());
                preparedSql = prepared.sql();
                preparedParams = prepared.params();
            }
            final String finalPreparedSql = preparedSql;
            final List<Object> finalPreparedParams = preparedParams;
            return executor.withConnection(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(finalPreparedSql)) {
                    bind(ps, finalPreparedParams);
                    int paramCount = ps.getParameterMetaData().getParameterCount();
                    int colCount = 0;
                    try {
                        colCount = ps.getMetaData() != null ? ps.getMetaData().getColumnCount() : 0;
                    } catch (SQLException ignore) {
                        // some drivers can't describe a prepared SELECT without execution
                    }
                    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("valid", true);
                    body.put("parameters", paramCount);
                    body.put("columns", colCount);
                    return JsonWriter.write(body);
                }
            });
        } catch (RuntimeException e) {
            return validationFailure("params", e.getMessage());
        } catch (SQLException e) {
            return validationFailure("driver", e.getMessage());
        }
    }

    private static String validationFailure(String stage, String message) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("valid", false);
        body.put("stage", stage);
        body.put("error", message);
        return JsonWriter.write(body);
    }

    // ---------------- helpers ----------------

    private QueryResult query(String sql, List<Object> params, Map<String, Object> namedParams,
                              Integer limit, Integer timeoutSeconds) throws SQLException {
        SqlParameterBindingResolver.Binding binding = resolveBinding(sql, params, namedParams);
        if (binding.namedParams() != null) {
            return executor.queryNamed(sql, binding.namedParams(), limit, timeoutSeconds);
        }
        return executor.query(sql, binding.params(), limit, timeoutSeconds);
    }

    private SqlParameterBindingResolver.Binding resolveBinding(String sql, List<Object> params,
                                                              Map<String, Object> namedParams) {
        return SqlParameterBindingResolver.resolve(sql, params, namedParams);
    }

    private String normalizeSql(String sql) {
        if (sql == null || sql.indexOf('\\') < 0) {
            return sql;
        }
        return sql
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

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
