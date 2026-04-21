package ru.it_spectrum.ai.jdbc.mcp.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.OracleDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.plan.OraclePlanParser;
import ru.it_spectrum.ai.jdbc.mcp.plan.ParsedPlan;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanAnalyzer;
import ru.it_spectrum.ai.jdbc.mcp.sql.NamedParameterRewriter;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Read-only smoke tests against a real Oracle database.
 *
 * <p>Run with: {@code ./gradlew liveOracleTest}
 *
 * <p>Required environment variables (all tests are skipped if any is missing):
 * <ul>
 *   <li>{@code LIVE_ORACLE_URL} — e.g. {@code jdbc:oracle:thin:@db.example.com:1521:ORCL}</li>
 *   <li>{@code LIVE_ORACLE_USERNAME}</li>
 *   <li>{@code LIVE_ORACLE_PASSWORD}</li>
 *   <li>{@code LIVE_ORACLE_SCHEMA} — optional, defaults to username upper-cased</li>
 * </ul>
 *
 * <p>These tests NEVER write or create objects — they only execute SELECTs against
 * dictionary views ({@code DUAL}, {@code ALL_TABLES}) and the user's own schema.
 */
@Tag("live-oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveOracleIntegrationTest {

    private String url;
    private String username;
    private String password;
    private String schema;

    private SqlExecutor executor;
    private MetadataService metadata;
    private StatsService stats;
    private SqlDialect dialect;

    @BeforeAll
    void setup() {
        url = System.getenv("LIVE_ORACLE_URL");
        username = System.getenv("LIVE_ORACLE_USERNAME");
        password = System.getenv("LIVE_ORACLE_PASSWORD");
        Assumptions.assumeTrue(
                url != null && !url.isBlank()
                        && username != null && !username.isBlank()
                        && password != null && !password.isBlank(),
                "LIVE_ORACLE_URL / LIVE_ORACLE_USERNAME / LIVE_ORACLE_PASSWORD are not set — "
                        + "skipping live Oracle tests");

        String explicitSchema = System.getenv("LIVE_ORACLE_SCHEMA");
        schema = (explicitSchema != null && !explicitSchema.isBlank())
                ? explicitSchema.toUpperCase(Locale.ROOT)
                : username.toUpperCase(Locale.ROOT);

        JdbcProperties props = new JdbcProperties(
                url, username, password,
                schema, 30, 1000, 100, "strict");
        DataSource ds = buildPool(props);
        dialect = new OracleDialect();
        ReadOnlyGuard guard = new ReadOnlyGuard(props);
        executor = new SqlExecutor(ds, dialect, props, guard);
        metadata = new MetadataService(executor, dialect, props);
        stats = new StatsService(executor, dialect, props);
    }

    private DataSource buildPool(JdbcProperties p) {
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl(p.url());
        cfg.setUsername(p.username());
        cfg.setPassword(p.password());
        cfg.setReadOnly(true);
        cfg.setMaximumPoolSize(2);
        return new com.zaxxer.hikari.HikariDataSource(cfg);
    }

    @Test
    void pingViaDual() throws Exception {
        QueryResult r = executor.query("SELECT 1 AS v FROM dual", null, null, null);
        assertThat(r.rows()).hasSize(1);
        Object v = r.rows().get(0).get("V");
        if (v == null) v = r.rows().get(0).get("v");
        assertThat(((Number) v).intValue()).isEqualTo(1);
    }

    @Test
    void parameterisedCountAgainstAllTables() throws Exception {
        QueryResult r = executor.query(
                "SELECT COUNT(*) AS c FROM all_tables WHERE owner = ?",
                List.of(schema), null, null);
        assertThat(r.rows()).hasSize(1);
        Object c = r.rows().get(0).get("C");
        if (c == null) c = r.rows().get(0).get("c");
        assertThat(((Number) c).longValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void guardRejectsDelete() {
        assertThatThrownBy(() -> executor.query("DELETE FROM dual", null, null, null))
                .hasMessageContaining("Only SELECT");
    }

    @Test
    void guardRejectsUpdate() {
        assertThatThrownBy(() -> executor.query("UPDATE dual SET dummy = 'X'", null, null, null))
                .hasMessageContaining("Only SELECT");
    }

    @Test
    void listSchemasIncludesUserSchema() throws Exception {
        List<String> schemas = metadata.listSchemas(false);
        assertThat(schemas).isNotEmpty();
        assertThat(schemas).contains(schema);
    }

    @Test
    void listTablesReturnsSomethingForUserSchema() throws Exception {
        List<Map<String, Object>> tables = metadata.listTables(schema, null, null);
        // Not asserting non-empty — a schema may legitimately be empty. Just must not throw.
        assertThat(tables).isNotNull();
    }

    @Test
    void describeFirstTableReturnsColumns() throws Exception {
        List<Map<String, Object>> tables = metadata.listTables(schema, null, new String[]{"TABLE"});
        Assumptions.assumeTrue(tables != null && !tables.isEmpty(),
                "no tables in schema " + schema + " — skipping describeTable check");
        String tableName = String.valueOf(tables.get(0).get("name"));
        Map<String, Object> info = metadata.describeTable(schema, tableName);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) info.get("columns");
        assertThat(cols).isNotEmpty();
    }

    @Test
    void tableStatsForFirstTable() throws Exception {
        List<Map<String, Object>> tables = metadata.listTables(schema, null, new String[]{"TABLE"});
        Assumptions.assumeTrue(tables != null && !tables.isEmpty(),
                "no tables in schema " + schema + " — skipping tableStats check");
        String tableName = String.valueOf(tables.get(0).get("name"));
        Map<String, Object> s = stats.tableStats(schema, tableName);
        // 'found' may be false if the user can't see DBA_*/ALL_TAB_STATISTICS for this table —
        // that's still a valid result (MCP tool would report it as-is).
        assertThat(s).containsKey("found");
    }

    @Test
    void describeLiqBankExecutiveTableRepeatedly() throws Exception {
        // Call describeTable multiple times sequentially to check for resource leaks
        for (int i = 0; i < 5; i++) {
            Map<String, Object> info = metadata.describeTable(schema, "LIQ_BANK_EXECUTIVE");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) info.get("columns");
            assertThat(cols).isNotEmpty();
        }
    }

    @Test
    void describeLiqBankExecutiveTableConcurrently() throws Exception {
        // Simulate multiple concurrent requests to MCP server
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    Map<String, Object> info = metadata.describeTable(schema, "LIQ_BANK_EXECUTIVE");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> cols = (List<Map<String, Object>>) info.get("columns");
                    if (!cols.isEmpty()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("all threads completed within timeout").isTrue();
        if (error.get() != null) {
            throw error.get();
        }
        assertThat(successCount.get()).isEqualTo(threadCount);
    }

    @Test
    void explainAndAnalyzeComplexPermissionsQuery() throws Exception {
        // Complex query with multiple correlated subqueries — test EXPLAIN PLAN + structuredPlanQuery.
        // Uses named parameters (as they come from MCP tools) substituted with dummy values for EXPLAIN.
        String sqlTemplate = """
            select sf.system_function_id,
                   sga.system_grid_id,
                   sga.system_action_id,
                   sa.sa_class_name
              from system_function sf,
                   SYSTEM_FUNCTION_GRID sfg,
                   SYSTEM_GRID_ACTION sga,
                   SYSTEM_ACTION sa
             where sfg.system_function_id = sf.system_function_id
               and sga.system_grid_id = sfg.system_grid_id
               and sf.SUBSYSTEM_CODE = :SubSystemCode
               and sa.system_action_id = sga.system_action_id
               and ( (:SubSystemCode IN ('A', 'K', 'C')
                      or UPPER(sga.sga_params) like '%COMMONSUBSYSTEMFUNCTION%'
                      or UPPER(sa.sa_parameters) like '%COMMONSUBSYSTEMFUNCTION%')
                    and
                    (exists (select 1 from ROLE_FUNCTION_ACTION rfa
                             join EMP_ROLE_NN_USER eru on eru.EMP_ROLE_ID = rfa.EMP_ROLE_ID
                             where eru.USER_ID = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                     or
                     exists (select 1 from ROLE_FUNCTION_ACTION rfa
                             join INSERTED_ROLE ir on ir.EMP_EMP_ROLE_ID = rfa.EMP_ROLE_ID
                             join LIQ_BANK_USER_ROLE eru on eru.EMP_ROLE_ID = ir.EMP_ROLE_ID
                             where eru.USER_ID = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                     or
                     exists (select 1 from USER_GROUP_USER ugu
                             join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                             join INSERTED_ROLE ir on ir.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                             join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ir.EMP_EMP_ROLE_ID
                             join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                          and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                             where ugu.user_id = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                     or
                     exists (select 1 from USER_GROUP_USER ugu
                             join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                             join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ugr.emp_role_id
                             join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                          and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                             where ugu.user_id = :UserId
                               and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                               and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                               and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID))
                   )
               or
               (:SubSystemCode not in ('A', 'K', 'C')
                and
                (exists (select 1 from ROLE_FUNCTION_ACTION rfa
                         join LIQ_BANK_USER_ROLE eru on eru.EMP_ROLE_ID = rfa.EMP_ROLE_ID
                         where eru.USER_ID = :UserId
                           and eru.LIQ_BANK_DIR_CODE = :LiqBankId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                 or
                 exists (select 1 from ROLE_FUNCTION_ACTION rfa
                         join INSERTED_ROLE ir on ir.EMP_EMP_ROLE_ID = rfa.EMP_ROLE_ID
                         join LIQ_BANK_USER_ROLE eru on eru.EMP_ROLE_ID = ir.EMP_ROLE_ID
                         where eru.USER_ID = :UserId
                           and eru.LIQ_BANK_DIR_CODE = :LiqBankId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                 or
                 exists (select 1 from USER_GROUP_USER ugu
                         join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                         join INSERTED_ROLE ir on ir.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                         join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ir.EMP_EMP_ROLE_ID
                         join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                      and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                         where ugu.user_id = :UserId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID)
                 or
                 exists (select 1 from USER_GROUP_USER ugu
                         join USER_GROUP_ROLE ugr on ugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                         join ROLE_FUNCTION_ACTION rfa on rfa.emp_role_id = ugr.emp_role_id
                         join LIQ_BANK_USER_GROUP_ROLE lbugr on lbugr.USER_GROUP_ID = ugu.USER_GROUP_ID
                                                      and lbugr.EMP_ROLE_ID = ugr.EMP_ROLE_ID
                         where ugu.user_id = :UserId
                           and rfa.SYSTEM_FUNCTION_ID = sfg.SYSTEM_FUNCTION_ID
                           and rfa.SYSTEM_GRID_ID = sga.SYSTEM_GRID_ID
                           and rfa.SYSTEM_ACTION_ID = sga.SYSTEM_ACTION_ID))
               )
            """;

        Map<String, Object> namedParams = Map.of(
                "SubSystemCode", "A",
                "UserId", "DUMMY_USER",
                "LiqBankId", 1);

        NamedParameterRewriter.PreparedSql explain = NamedParameterRewriter.rewrite(
                dialect.buildExplain(sqlTemplate, false), namedParams);
        QueryResult planRows = executor.withConnection(conn -> {
            try (var ps = conn.prepareStatement(explain.sql())) {
                for (int i = 0; i < explain.params().size(); i++) {
                    ps.setObject(i + 1, explain.params().get(i));
                }
                ps.execute();
            }
            try (var ps = conn.prepareStatement(dialect.explainDisplayQuery());
                 var rs = ps.executeQuery()) {
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
        });
        StringBuilder planText = new StringBuilder();
        for (Map<String, Object> row : planRows.rows()) {
            Object v = row.get("PLAN");
            if (v == null) v = row.get("plan");
            if (v != null) planText.append(v).append("\n");
        }
        assertThat(planText.toString()).isNotBlank();

        // Step 2: structuredPlanQuery → OraclePlanParser → PlanAnalyzer.summarize
        NamedParameterRewriter.PreparedSql structuredExplain = NamedParameterRewriter.rewrite(
                dialect.buildStructuredExplain(sqlTemplate, false), namedParams);
        QueryResult structuredRows = executor.withConnection(conn -> {
            try (var ps = conn.prepareStatement(structuredExplain.sql())) {
                for (int i = 0; i < structuredExplain.params().size(); i++) {
                    ps.setObject(i + 1, structuredExplain.params().get(i));
                }
                ps.execute();
            }
            try (var ps = conn.prepareStatement(dialect.structuredPlanQuery());
                 var rs = ps.executeQuery()) {
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
        });
        ParsedPlan plan = new OraclePlanParser().parse(structuredRows, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) (Map<?, ?>) PlanAnalyzer.summarize(plan);

        assertThat(summary).containsKeys("engine", "analyzed", "node_count", "top_expensive_nodes");
        assertThat(summary.get("engine")).isEqualTo("oracle");
    }
}
