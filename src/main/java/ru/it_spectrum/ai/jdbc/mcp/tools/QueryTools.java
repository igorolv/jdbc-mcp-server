package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.plan.ParsedPlan;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanAnalyzer;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser;
import ru.it_spectrum.ai.jdbc.mcp.sql.NamedParameterRewriter;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryLintService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParameterBindingResolver;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlNotAllowedException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
    private final QueryAnalysisService analysis;
    private final QueryLintService lint;
    private final JsonResponses json;
    private final ToolErrors errors;

    public QueryTools(SqlExecutor executor, SqlDialect dialect,
                      JdbcProperties properties, ReadOnlyGuard guard,
                      PlanParser planParser,
                      QueryAnalysisService analysis,
                      QueryLintService lint,
                      JsonResponses json,
                      ToolErrors errors) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.guard = guard;
        this.planParser = planParser;
        this.analysis = analysis;
        this.lint = lint;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(description = "Execute a read-only SQL SELECT / WITH / EXPLAIN statement and return the result. " +
            "Only pure read statements are allowed — write operations (INSERT, UPDATE, DELETE, DDL, etc.) " +
            "are rejected before being sent to the database. " +
            BINDING_RULES +
            BINDING_EXAMPLES +
            "Returns JSON. Results are truncated to 'limit' rows (default JDBC_MAX_ROWS) with a 'truncated' marker.")
    public String executeQuery(
            @McpToolParam(description = "SQL statement (SELECT, WITH, or EXPLAIN)") String sql,
            @McpToolParam(description = "Positional parameters for '?' placeholders, in order. Required when SQL contains '?'.", required = false) List<Object> params,
            @McpToolParam(description = "Named parameters for ':name' placeholders. Required when SQL contains ':name'.", required = false) Map<String, Object> namedParams,
            @McpToolParam(description = "Max rows to return (optional, default JDBC_MAX_ROWS)", required = false) Integer limit,
            @McpToolParam(description = "Per-query timeout in seconds (optional, default JDBC_QUERY_TIMEOUT_SECONDS)", required = false) Integer timeoutSeconds
    ) {
        try {
            String normalizedSql = normalizeSql(sql);
            QueryResult result = query(normalizedSql, params, namedParams, limit, timeoutSeconds);
            return json.write(result);
        } catch (SqlNotAllowedException e) {
            return errors.rejected(e);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Return the execution plan for a SQL SELECT / WITH statement. " +
            "PostgreSQL: uses EXPLAIN (FORMAT TEXT); with analyze=true runs EXPLAIN ANALYZE (note: this actually executes the query!). " +
            "Oracle: uses EXPLAIN PLAN + DBMS_XPLAN.DISPLAY; analyze flag is ignored (Oracle returns a static plan). " +
            "SQL Server: uses SET SHOWPLAN_TEXT ON for an estimated plan; analyze flag is ignored. " +
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
            if (dialect.kind() == DatabaseKind.MSSQL) {
                return explainSqlServer(normalizedSql, params, namedParams);
            }
            String statementId = newExplainStatementId();
            String explainSql = dialect.buildExplain(normalizedSql, doAnalyze, statementId);
            String displaySql = dialect.explainDisplayQuery(statementId);
            List<Object> displayParams = displaySql == null ? Collections.emptyList() : List.of(statementId);

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
                    QueryResult planRows = queryWithParams(conn, displaySql, displayParams);
                    return rowsAsText(planRows);
                }
                QueryResult planRows = queryWithParams(conn, preparedExplainSql, preparedParams);
                return rowsAsText(planRows);
            }, displaySql == null);
        } catch (SqlNotAllowedException e) {
            return errors.rejected(e);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (Exception e) {
            return errors.unexpected(e);
        }
    }

    @McpTool(description = "Run a structured EXPLAIN and return a compact, LLM-friendly summary of the " +
            "execution plan instead of the full dump: top expensive nodes, full table scans on large " +
            "relations, estimation errors (planner vs. reality — requires analyze=true on PG), risky " +
            "nested loops with large outer inputs, and disk-sort spills. " +
            "PostgreSQL: uses EXPLAIN (FORMAT JSON); analyze=true switches to EXPLAIN ANALYZE (the query is executed!). " +
            "Oracle: uses EXPLAIN PLAN + PLAN_TABLE; analyze flag is ignored (static plan only, no actual rows / times). " +
            "SQL Server: uses SET SHOWPLAN_XML ON; analyze flag is ignored (estimated plan only). " +
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
            if (dialect.kind() == DatabaseKind.MSSQL) {
                ParsedPlan parsed = structuredSqlServerPlan(normalizedSql, params, namedParams);
                return json.write(PlanAnalyzer.summarize(parsed));
            }
            String statementId = newExplainStatementId();
            String explainSql = dialect.buildStructuredExplain(normalizedSql, doAnalyze, statementId);
            String displaySql = dialect.structuredPlanQuery(statementId);
            List<Object> displayParams = displaySql == null ? Collections.emptyList() : List.of(statementId);

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
                    planRows = queryWithParams(conn, displaySql, displayParams);
                } else {
                    // PostgreSQL: FORMAT JSON EXPLAIN returns one row with the plan document.
                    planRows = queryWithParams(conn, preparedExplainSql, preparedParams);
                }
                return planParser.parse(planRows, doAnalyze);
            }, displaySql == null);
            return json.write(PlanAnalyzer.summarize(parsed));
        } catch (SqlNotAllowedException e) {
            return errors.rejected(e);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.planParse(e);
        } catch (Exception e) {
            return errors.unexpected(e);
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
            return validationFailure("guard", e.getMessage(), analysis.inspect(normalizedSql));
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
                    body.put("inspection", analysis.inspect(normalizedSql));
                    return json.write(body);
                }
            });
        } catch (RuntimeException e) {
            return validationFailure("params", e.getMessage(), analysis.inspect(normalizedSql));
        } catch (SQLException e) {
            return validationFailure("driver", e.getMessage(), analysis.inspect(normalizedSql));
        }
    }

    @McpTool(description = "Parse SQL with JSqlParser and return an AST-derived summary for LLM query authoring: " +
            "tables, aliases, selected expressions, joins, predicates, order by, referenced columns, parameters, " +
            "features and parser-level warnings. This is informational only; it does not execute SQL and does not " +
            "replace the read-only guard or driver validation.")
    public String inspectQuery(
            @McpToolParam(description = "SQL statement to inspect") String sql
    ) {
        return json.write(analysis.inspect(normalizeSql(sql)));
    }

    @McpTool(description = "Parse SQL and run metadata-aware lint checks for LLM query authoring. " +
            "Returns advisory warnings such as unknown table/column, SELECT *, joins without conditions, " +
            "FKs without supporting indexes, and predicate/order-by columns that are not leading columns " +
            "of any visible index. This tool does not execute SQL and never blocks execution.")
    public String queryLint(
            @McpToolParam(description = "SQL statement to lint") String sql,
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema
    ) {
        try {
            return json.write(lint.lint(normalizeSql(sql), schema));
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    private String validationFailure(String stage, String message, Object inspection) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("valid", false);
        body.put("stage", stage);
        body.put("error", message);
        body.put("inspection", inspection);
        return json.write(body);
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

    private String explainSqlServer(String sql, List<Object> params, Map<String, Object> namedParams)
            throws SQLException {
        return executor.withConnection(conn -> {
            String planSql = sqlServerPlanSql(sql, params, namedParams);
            runStatement(conn, "SET SHOWPLAN_TEXT ON");
            try {
                QueryResult planRows = queryWithStatement(conn, planSql);
                return rowsAsText(planRows);
            } finally {
                runStatement(conn, "SET SHOWPLAN_TEXT OFF");
            }
        });
    }

    private ParsedPlan structuredSqlServerPlan(String sql, List<Object> params, Map<String, Object> namedParams)
            throws SQLException {
        return executor.withConnection(conn -> {
            String planSql = sqlServerPlanSql(sql, params, namedParams);
            runStatement(conn, "SET SHOWPLAN_XML ON");
            try {
                QueryResult planRows = queryWithStatement(conn, planSql);
                return planParser.parse(planRows, false);
            } finally {
                runStatement(conn, "SET SHOWPLAN_XML OFF");
            }
        });
    }

    private String sqlServerPlanSql(String sql, List<Object> params, Map<String, Object> namedParams) {
        SqlParameterBindingResolver.Binding binding = resolveBinding(sql, params, namedParams);
        if (binding.namedParams() != null) {
            NamedParameterRewriter.PreparedSql prep =
                    NamedParameterRewriter.rewrite(sql, binding.namedParams());
            return inlinePositionalParams(prep.sql(), prep.params());
        }
        return inlinePositionalParams(sql, binding.params());
    }

    private QueryResult queryWithStatement(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (properties.queryTimeoutSeconds() > 0) st.setQueryTimeout(properties.queryTimeoutSeconds());
            try (var rs = st.executeQuery(sql)) {
                return readAll(rs);
            }
        }
    }

    private void runStatement(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (properties.queryTimeoutSeconds() > 0) st.setQueryTimeout(properties.queryTimeoutSeconds());
            st.execute(sql);
        }
    }

    private String inlinePositionalParams(String sql, List<Object> params) {
        if (params == null || params.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length() + params.size() * 8);
        int param = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBracket = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                out.append(c);
                if (c == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append(sql.charAt(++i));
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                out.append(c);
                if (c == '"' && i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    out.append(sql.charAt(++i));
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBracket) {
                out.append(c);
                if (c == ']' && i + 1 < sql.length() && sql.charAt(i + 1) == ']') {
                    out.append(sql.charAt(++i));
                } else if (c == ']') {
                    inBracket = false;
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i + 2);
                if (end < 0) end = sql.length();
                out.append(sql, i, end);
                i = end - 1;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) end = sql.length() - 2;
                out.append(sql, i, Math.min(sql.length(), end + 2));
                i = Math.min(sql.length() - 1, end + 1);
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                out.append(c);
            } else if (c == '"') {
                inDouble = true;
                out.append(c);
            } else if (c == '[') {
                inBracket = true;
                out.append(c);
            } else if (c == '?') {
                if (param >= params.size()) {
                    throw new IllegalArgumentException("Not enough params for SQL Server SHOWPLAN query");
                }
                out.append(sqlLiteral(params.get(param++)));
            } else {
                out.append(c);
            }
        }
        if (param != params.size()) {
            throw new IllegalArgumentException("Too many params for SQL Server SHOWPLAN query");
        }
        return out.toString();
    }

    private String sqlLiteral(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number n) return n.toString();
        if (value instanceof Boolean b) return b ? "1" : "0";
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder("0x");
            for (byte b : bytes) hex.append(String.format("%02X", b));
            return hex.toString();
        }
        if (value instanceof java.sql.Date || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp || value instanceof TemporalAccessor) {
            return "N'" + escapeSqlLiteral(value.toString()) + "'";
        }
        return "N'" + escapeSqlLiteral(value.toString()) + "'";
    }

    private String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private String newExplainStatementId() {
        int random = ThreadLocalRandom.current().nextInt(36 * 36 * 36);
        return "JDBC_MCP_" + Long.toString(System.nanoTime(), 36) + "_"
                + Integer.toString(random, 36);
    }

    // Kept to prevent IDE "unused" warnings; the guard instance is injected so Spring wires it.
    @SuppressWarnings("unused")
    private List<Object> noParams() {
        return Collections.emptyList();
    }

}
