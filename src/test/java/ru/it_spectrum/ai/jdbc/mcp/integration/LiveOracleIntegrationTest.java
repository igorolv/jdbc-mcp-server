package ru.it_spectrum.ai.jdbc.mcp.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.dialect.OracleDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.TableStats;
import ru.it_spectrum.ai.jdbc.mcp.plan.OraclePlanParser;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryLineageService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryLintService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.tools.QueryTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.ToolErrors;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.ProceduralSqlExtractor;

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
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

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
    private QueryTools queryTools;

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
                schema, 30, 1000, 100, "strict", 40, 1, 10_000, 5_000, 300, 2000);
        DataSource ds = buildPool(props);
        dialect = new OracleDialect();
        ReadOnlyGuard guard = new ReadOnlyGuard(props);
        executor = new SqlExecutor(ds, dialect, props, guard);
        metadata = new MetadataService(executor, dialect, props);
        stats = new StatsService(executor, dialect, props);
        QueryAnalysisService analysis = new QueryAnalysisService();
        QueryLineageService lineage = new QueryLineageService(analysis, metadata, new ProceduralSqlExtractor());
        QueryLintService lint = new QueryLintService(analysis, metadata, stats);
        JsonResponses json = new JsonResponses(new JsonConfig().jdbcMcpObjectMapper());
        ToolErrors errors = new ToolErrors(json);
        queryTools = new QueryTools(executor, dialect, props, guard, new OraclePlanParser(), analysis, lineage, lint, json, errors);
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
        List<TableEntry> tables = metadata.listTables(schema, null, null);
        // Not asserting non-empty — a schema may legitimately be empty. Just must not throw.
        assertThat(tables).isNotNull();
    }

    @Test
    void describeFirstTableReturnsColumns() throws Exception {
        List<TableEntry> tables = metadata.listTables(schema, null, new String[]{"TABLE"});
        Assumptions.assumeTrue(tables != null && !tables.isEmpty(),
                "no tables in schema " + schema + " — skipping describeTable check");
        String tableName = tables.get(0).name();
        TableDescription info = metadata.describeTable(schema, tableName);
        List<Column> cols = info.columns();
        assertThat(cols).isNotEmpty();
    }

    @Test
    void tableStatsForFirstTable() throws Exception {
        List<TableEntry> tables = metadata.listTables(schema, null, new String[]{"TABLE"});
        Assumptions.assumeTrue(tables != null && !tables.isEmpty(),
                "no tables in schema " + schema + " — skipping tableStats check");
        String tableName = tables.get(0).name();
        TableStats s = stats.tableStats(schema, tableName);
        // 'found' may be false if the user can't see DBA_*/ALL_TAB_STATISTICS for this table —
        // that's still a valid result (MCP tool would report it as-is).
        assertThat(s).isNotNull();
    }

    @Test
    void explainQueryNamedParamsWithInList_doesNotDoubleRewrite() {
        // Regression test for ORA-17041: when named parameters include an IN-list expression
        // like ":p IN ('A', 'K', 'C')", the original QueryTools code called
        // NamedParameterRewriter.rewrite() twice — once in prepareSql() and once in
        // prepareParams() — causing the second parse on the original SQL to misinterpret
        // the ':' inside the IN-list, yielding a mismatched parameter count and ORA-17041.
        // The fix consolidates both steps into a single rewrite call.
        String sql = """
            SELECT 1 FROM dual
             WHERE :code IN ('A', 'K', 'C')
                OR (:code NOT IN ('A', 'K', 'C') AND :userId IS NOT NULL)
            """;
        Map<String, Object> namedParams = Map.of(
                "code", "A",
                "userId", "DUMMY_USER");

        String explainResult = queryTools.explainQuery(sql, null, namedParams, null);
        assertThat(explainResult)
                .doesNotContain("ORA-17041")
                .doesNotContain("SQL error");

        String analyzeResult = queryTools.analyzePlan(sql, null, namedParams, null);
        assertThat(analyzeResult)
                .doesNotContain("ORA-17041")
                .doesNotContain("SQL error")
                .doesNotContain("Plan parse error");
    }
}
