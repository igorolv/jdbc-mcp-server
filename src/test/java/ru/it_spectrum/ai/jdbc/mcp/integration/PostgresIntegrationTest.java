package ru.it_spectrum.ai.jdbc.mcp.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.PostgresDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.plan.ParsedPlan;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanAnalyzer;
import ru.it_spectrum.ai.jdbc.mcp.plan.PostgresPlanParser;
import ru.it_spectrum.ai.jdbc.mcp.sql.BenchmarkService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end check against a real PostgreSQL instance via Testcontainers.
 * Run with: {@code ./gradlew integrationTest}
 */
@Tag("integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jdbcmcp")
            .withUsername("jdbcmcp")
            .withPassword("jdbcmcp");

    private SqlExecutor executor;
    private MetadataService metadata;
    private StatsService stats;
    private DistributionService distribution;
    private BenchmarkService benchmarks;
    private SqlDialect dialect;

    @BeforeAll
    void setup() throws Exception {
        PG.start();
        try (Connection c = pgAdmin()) {
            try (var st = c.createStatement()) {
                st.execute("CREATE TABLE customers (id SERIAL PRIMARY KEY, name TEXT NOT NULL, email TEXT UNIQUE)");
                st.execute("CREATE INDEX idx_customers_name ON customers(name)");
                st.execute("CREATE TABLE orders (id SERIAL PRIMARY KEY, customer_id INT REFERENCES customers(id), total NUMERIC(10,2))");
                st.execute("INSERT INTO customers(name, email) VALUES ('Alice', 'a@example.com'), ('Bob', 'b@example.com')");
                st.execute("INSERT INTO orders(customer_id, total) VALUES (1, 10.5), (1, 20.0), (2, 5.0)");
                st.execute("COMMENT ON TABLE customers IS 'Customer master data'");
                st.execute("CREATE VIEW v_customer_totals AS SELECT c.id, c.name, COALESCE(SUM(o.total), 0) AS total FROM customers c LEFT JOIN orders o ON o.customer_id = c.id GROUP BY c.id, c.name");
            }
        }

        JdbcProperties props = new JdbcProperties(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(),
                "public", 10, 1000, 100, "strict");
        DataSource ds = buildPool(props);
        dialect = new PostgresDialect();
        ReadOnlyGuard guard = new ReadOnlyGuard(props);
        executor = new SqlExecutor(ds, dialect, props, guard);
        metadata = new MetadataService(executor, dialect, props);
        stats    = new StatsService(executor, dialect, props);
        distribution = new DistributionService(executor, dialect, props, new PostgresPlanParser());
        benchmarks = new BenchmarkService(executor, dialect);

        // Seed extra objects used by stats tests: a table with a redundant-prefix index,
        // a FK without an index, and an ANALYZE pass so pg_stat_user_tables has rows.
        try (Connection c = pgAdmin(); var st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS line_items ("
                    + "id SERIAL PRIMARY KEY, "
                    + "order_id INT REFERENCES orders(id), "
                    + "sku TEXT, "
                    + "qty INT)");
            // Composite index with leading (order_id, sku) makes a pure (order_id) index redundant.
            st.execute("CREATE INDEX IF NOT EXISTS idx_li_order_sku ON line_items(order_id, sku)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_li_order     ON line_items(order_id)");
            // Seed rows so size/row counts are non-zero.
            st.execute("INSERT INTO line_items(order_id, sku, qty) VALUES (1,'a',1),(1,'b',2),(2,'a',3) "
                    + "ON CONFLICT DO NOTHING");
            // Events table with heavy skew on "status" + some NULLs on "category".
            // Distribution/null-ratio/selectivity tests reason over this table.
            st.execute("CREATE TABLE IF NOT EXISTS events ("
                    + "id SERIAL PRIMARY KEY, "
                    + "status TEXT NOT NULL, "
                    + "category TEXT, "
                    + "amount NUMERIC(10,2), "
                    + "created_at TIMESTAMP DEFAULT NOW())");
            // Deterministic seed: 90 OK + 10 FAIL; category populated only on OK rows.
            st.execute("INSERT INTO events(status, category, amount) "
                    + "SELECT 'OK', CASE WHEN g % 2 = 0 THEN 'A' ELSE 'B' END, (g * 1.5)::numeric(10,2) "
                    + "FROM generate_series(1, 90) g");
            st.execute("INSERT INTO events(status, category, amount) "
                    + "SELECT 'FAIL', NULL, (g * 0.1)::numeric(10,2) "
                    + "FROM generate_series(1, 10) g");
            st.execute("ANALYZE customers");
            st.execute("ANALYZE orders");
            st.execute("ANALYZE line_items");
            st.execute("ANALYZE events");
        }
    }

    private Connection pgAdmin() throws SQLException {
        return java.sql.DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private DataSource buildPool(JdbcProperties p) {
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl(ru.it_spectrum.ai.jdbc.mcp.config.DataSourceConfig
                .applyDialectUrlTweaks(p.url(), DatabaseKind.POSTGRESQL));
        cfg.setUsername(p.username());
        cfg.setPassword(p.password());
        cfg.setReadOnly(true);
        cfg.setMaximumPoolSize(2);
        return new com.zaxxer.hikari.HikariDataSource(cfg);
    }

    @Test
    void executeSimpleSelect() throws Exception {
        QueryResult r = executor.query("SELECT name, email FROM customers ORDER BY id", null, null, null);
        assertThat(r.columns()).containsExactly("name", "email");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).get("name")).isEqualTo("Alice");
    }

    @Test
    void executeParameterisedSelect() throws Exception {
        QueryResult r = executor.query("SELECT COUNT(*) AS c FROM orders WHERE customer_id = ?",
                List.of(1), null, null);
        assertThat(r.rows()).hasSize(1);
        assertThat(((Number) r.rows().get(0).get("c")).intValue()).isEqualTo(2);
    }

    @Test
    void executeNamedParameterSelect() throws Exception {
        QueryResult r = executor.queryNamed(
                "SELECT COUNT(*) AS c FROM orders WHERE customer_id = :customerId",
                Map.of("customerId", 1), null, null);
        assertThat(r.rows()).hasSize(1);
        assertThat(((Number) r.rows().get(0).get("c")).intValue()).isEqualTo(2);
    }

    @Test
    void enforcesRowLimit() throws Exception {
        // Request limit of 1 — we have 2 customers, expect truncation
        QueryResult r = executor.query("SELECT * FROM customers ORDER BY id", null, 1, null);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void rejectsWriteViaGuard() {
        assertThatThrownBy(() -> executor.query("DELETE FROM customers", null, null, null))
                .hasMessageContaining("Only SELECT");
    }

    @Test
    void rejectsWriteAtTransactionLevel() throws Exception {
        // Guard is in place, but to prove the PG transaction is read-only too we disable the guard
        // and try a raw statement — server must refuse due to default_transaction_read_only=on.
        JdbcProperties propsNoGuard = new JdbcProperties(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(),
                "public", 10, 1000, 100, "off");
        DataSource ds = buildPool(propsNoGuard);
        try (Connection c = ds.getConnection()) {
            c.setReadOnly(true);
            try (var st = c.createStatement()) {
                assertThatThrownBy(() -> st.execute("DELETE FROM customers"))
                        .hasMessageContaining("read-only");
            }
        }
    }

    @Test
    void listSchemasAndTables() throws Exception {
        assertThat(metadata.listSchemas(false)).contains("public");
        List<Map<String, Object>> tables = metadata.listTables("public", "%", null);
        assertThat(tables).anyMatch(t -> "customers".equals(t.get("name")));
        assertThat(tables).anyMatch(t -> "orders".equals(t.get("name")));
    }

    @Test
    void describeTableReturnsFullShape() throws Exception {
        Map<String, Object> info = metadata.describeTable("public", "orders");
        assertThat(info.get("name")).isEqualTo("orders");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) info.get("columns");
        assertThat(cols).extracting("name").contains("id", "customer_id", "total");
        @SuppressWarnings("unchecked")
        Map<String, Object> pk = (Map<String, Object>) info.get("primaryKey");
        assertThat(pk).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> pkCols = (List<String>) pk.get("columns");
        assertThat(pkCols).containsExactly("id");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fks = (List<Map<String, Object>>) info.get("foreignKeys");
        assertThat(fks).hasSize(1);
        assertThat(fks.get(0).get("referencedTable")).isEqualTo("customers");
    }

    @Test
    void viewDefinitionIsReturned() throws Exception {
        String def = metadata.viewDefinition("public", "v_customer_totals");
        assertThat(def).containsIgnoringCase("FROM customers");
    }

    @Test
    void searchObjectsFindsViewAndTable() throws Exception {
        QueryResult r = metadata.searchObjects("customer");
        assertThat(r.rows()).extracting(m -> m.get("name"))
                .contains("customers", "v_customer_totals");
    }

    @Test
    void tableStatsReturnsSizeAndRowCounts() throws Exception {
        Map<String, Object> s = stats.tableStats("public", "customers");
        assertThat(s.get("found")).isEqualTo(Boolean.TRUE);
        assertThat(s.get("table_name")).isEqualTo("customers");
        // After seeding + ANALYZE, live_tuples is > 0 and sizes are non-null.
        assertThat(((Number) s.get("live_tuples")).longValue()).isGreaterThanOrEqualTo(2L);
        assertThat(((Number) s.get("total_size_bytes")).longValue()).isGreaterThan(0L);
        assertThat(((Number) s.get("indexes_size_bytes")).longValue()).isGreaterThan(0L);
    }

    @Test
    void indexStatsListsAllIndexes() throws Exception {
        QueryResult r = stats.indexStats("public", "customers");
        assertThat(r.rows()).extracting(m -> m.get("index_name"))
                .contains("customers_pkey", "idx_customers_name");
        // PK must be flagged as such.
        assertThat(r.rows()).anyMatch(m ->
                "customers_pkey".equals(m.get("index_name")) && Boolean.TRUE.equals(m.get("is_primary")));
    }

    @Test
    void fkIndexCoverageFlagsUnindexedFk() throws Exception {
        // orders(customer_id) references customers(id) but has no index on customer_id.
        @SuppressWarnings("unchecked")
        Map<String, Object> r = stats.fkIndexCoverage("public", "orders");
        assertThat(((Number) r.get("uncovered_count")).intValue()).isGreaterThanOrEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.get("uncovered");
        assertThat(list).anyMatch(e ->
                "orders".equals(e.get("table"))
                        && e.get("fk_columns") instanceof List<?> cs
                        && cs.contains("customer_id"));
    }

    @Test
    void redundantIndexesDetectsPrefixShadowing() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> r = stats.redundantIndexes("public", "line_items");
        assertThat(((Number) r.get("count")).intValue()).isGreaterThanOrEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) r.get("findings");
        // idx_li_order (order_id) is shadowed by idx_li_order_sku (order_id, sku)
        assertThat(findings).anyMatch(f ->
                "idx_li_order".equals(f.get("shadowed_index"))
                        && "idx_li_order_sku".equals(f.get("covered_by_index")));
    }

    @Test
    void structuredExplainParsesAndSummarises() throws Exception {
        // Run the JSON EXPLAIN through the dialect + parser + analyser and check that the
        // summary mentions the target relation as a full scan (customers is tiny, so we pass
        // its current seq-scan plan through without asserting thresholds — the shape is what
        // matters).
        String explainSql = dialect.buildStructuredExplain(
                "SELECT * FROM customers WHERE name LIKE 'A%'", false);
        QueryResult r = executor.query(explainSql, null, null, null);
        ParsedPlan plan = new PostgresPlanParser().parse(r, false);

        assertThat(plan.engine()).isEqualTo("postgresql");
        assertThat(plan.root()).isNotNull();
        assertThat(plan.root().nodeType()).isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) (Map<?, ?>) PlanAnalyzer.summarize(plan);
        assertThat(summary).containsKeys("engine", "analyzed", "node_count", "top_expensive_nodes");
        assertThat(summary.get("engine")).isEqualTo("postgresql");
    }

    @Test
    void columnDistributionExposesSkew() throws Exception {
        // events has 90 rows with status='OK' and 10 with status='FAIL' — top distribution
        // must reflect that 9:1 ratio and sum to 100 % of the table.
        @SuppressWarnings("unchecked")
        Map<String, Object> r = distribution.columnDistribution("public", "events", "status", 5);
        assertThat(((Number) r.get("total_rows")).longValue()).isEqualTo(100L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> values = (List<Map<String, Object>>) r.get("values");
        assertThat(values).hasSize(2);
        assertThat(values.get(0).get("value")).isEqualTo("OK");
        assertThat(((Number) values.get(0).get("frequency")).longValue()).isEqualTo(90L);
        assertThat(((Number) values.get(0).get("ratio")).doubleValue()).isCloseTo(0.9, within(1e-6));
        assertThat(values.get(1).get("value")).isEqualTo("FAIL");
        // All rows are covered by the top-N, so other_rows should be zero.
        assertThat(((Number) r.get("other_rows")).longValue()).isEqualTo(0L);
    }

    @Test
    void columnHistogramReportsPercentiles() throws Exception {
        Map<String, Object> r = distribution.columnHistogram("public", "events", "amount");
        assertThat(((Number) r.get("total_rows")).longValue()).isEqualTo(100L);
        assertThat(r.get("percentile_function")).isEqualTo("percentile_cont");
        // All 100 rows non-null → null_ratio == 0.
        assertThat(((Number) r.get("null_rows")).longValue()).isEqualTo(0L);
        // P50 must fall between MIN and MAX.
        double min = ((Number) r.get("min")).doubleValue();
        double max = ((Number) r.get("max")).doubleValue();
        double p50 = ((Number) r.get("p50")).doubleValue();
        double p99 = ((Number) r.get("p99")).doubleValue();
        assertThat(p50).isBetween(min, max);
        assertThat(p99).isGreaterThanOrEqualTo(p50);
    }

    @Test
    void nullRatioFlagsSparseColumns() throws Exception {
        Map<String, Object> r = distribution.nullRatio("public", "events");
        assertThat(((Number) r.get("total_rows")).longValue()).isEqualTo(100L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) r.get("columns");
        // The 'category' column is null on 10 rows out of 100 (10 %) — non-sparse.
        Map<String, Object> category = cols.stream()
                .filter(c -> "category".equals(c.get("column")))
                .findFirst().orElseThrow();
        assertThat(((Number) category.get("null_rows")).longValue()).isEqualTo(10L);
        assertThat(((Number) category.get("null_ratio")).doubleValue()).isCloseTo(0.1, within(1e-6));
        assertThat(category.get("sparse")).isEqualTo(Boolean.FALSE);
        // status / id / amount / created_at are all non-nullable → zero nulls.
        Map<String, Object> status = cols.stream()
                .filter(c -> "status".equals(c.get("column")))
                .findFirst().orElseThrow();
        assertThat(((Number) status.get("null_rows")).longValue()).isEqualTo(0L);
    }

    @Test
    void estimateSelectivityUsesExplain() throws Exception {
        // status='FAIL' matches 10/100 rows — planner estimate should be in that ballpark
        // and strictly smaller than the baseline.
        Map<String, Object> r = distribution.estimateSelectivity(
                "public", "events", "status = 'FAIL'");
        Long estimated = ((Number) r.get("estimated_rows")).longValue();
        Long baseline  = ((Number) r.get("baseline_rows")).longValue();
        assertThat(estimated).isNotNull();
        assertThat(baseline).isNotNull();
        assertThat(baseline).isGreaterThan(0L);
        assertThat(estimated).isLessThan(baseline);
        // Selectivity expressed as a ratio in [0, 1].
        double sel = ((Number) r.get("selectivity")).doubleValue();
        assertThat(sel).isBetween(0.0, 1.0);
    }

    @Test
    void joinCardinalityEstimatesInnerJoin() throws Exception {
        Map<String, Object> r = distribution.joinCardinality(
                "public", "customers", "id",
                "public", "orders", "customer_id", "INNER");
        assertThat(r.get("join_type")).isEqualTo("INNER");
        Long estimated = ((Number) r.get("estimated_rows")).longValue();
        assertThat(estimated).isNotNull();
        // 3 orders all match a customer → planner should estimate ~3; allow a loose bound.
        assertThat(estimated).isGreaterThan(0L);
        assertThat(estimated).isLessThan(1000L);
    }

    @Test
    void estimateSelectivityRejectsSemicolon() {
        assertThatThrownBy(() -> distribution.estimateSelectivity(
                "public", "events", "status = 'OK'; DROP TABLE events"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single boolean expression");
    }

    @Test
    void benchmarkQueryReturnsColdAndWarmStats() throws Exception {
        Map<String, Object> r = benchmarks.benchmark(
                "SELECT * FROM events ORDER BY id", (List<Object>) null,
                50, 5, 1, 3);
        assertThat(((Number) r.get("runs")).intValue()).isEqualTo(4);
        assertThat(((Number) r.get("cold_runs")).intValue()).isEqualTo(1);
        assertThat(((Number) r.get("warm_runs")).intValue()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        Map<String, Object> warm = (Map<String, Object>) r.get("warm_ms");
        assertThat(((Number) warm.get("runs")).intValue()).isEqualTo(3);
        double min = ((Number) warm.get("min")).doubleValue();
        double med = ((Number) warm.get("median")).doubleValue();
        double max = ((Number) warm.get("max")).doubleValue();
        assertThat(min).isGreaterThanOrEqualTo(0.0);
        assertThat(med).isGreaterThanOrEqualTo(min);
        assertThat(max).isGreaterThanOrEqualTo(med);

        @SuppressWarnings("unchecked")
        Map<String, Object> size = (Map<String, Object>) r.get("result_size");
        // limit=50 and events has 100 rows → truncated with row_count == 50.
        assertThat(((Number) size.get("row_count")).intValue()).isEqualTo(50);
        assertThat(size.get("truncated")).isEqualTo(Boolean.TRUE);

        @SuppressWarnings("unchecked")
        List<Object> allMs = (List<Object>) r.get("all_ms");
        assertThat(allMs).hasSize(4);
    }

    @Test
    void benchmarkQueryRequiresLimitAndTimeout() {
        assertThatThrownBy(() -> benchmarks.benchmark("SELECT 1", (List<Object>) null, 0, 5, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> benchmarks.benchmark("SELECT 1", (List<Object>) null, 10, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutSeconds");
        assertThatThrownBy(() -> benchmarks.benchmark("SELECT 1", (List<Object>) null, 10, 5, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void benchmarkQuerySupportsNamedParams() throws Exception {
        Map<String, Object> r = benchmarks.benchmark(
                "SELECT * FROM events WHERE status = :status ORDER BY id",
                Map.of("status", "OK"),
                50, 5, 1, 2);
        assertThat(((Number) r.get("runs")).intValue()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> size = (Map<String, Object>) r.get("result_size");
        assertThat(((Number) size.get("row_count")).intValue()).isEqualTo(50);
        assertThat(size.get("truncated")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void benchmarkQueryRejectsWrites() {
        assertThatThrownBy(() -> benchmarks.benchmark("DELETE FROM events", (List<Object>) null, 10, 5, 1, 1))
                .hasMessageContaining("Only SELECT");
    }

    @Test
    void timedQueryReturnsElapsedAndRows() throws Exception {
        Map<String, Object> r = benchmarks.timed(
                "SELECT * FROM events WHERE status = ?", List.of("OK"), 200, 5);
        assertThat(((Number) r.get("elapsed_ms")).doubleValue()).isGreaterThanOrEqualTo(0.0);
        assertThat(((Number) r.get("row_count")).intValue()).isEqualTo(90);
        assertThat(r.get("truncated")).isEqualTo(Boolean.FALSE);
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) r.get("columns");
        assertThat(columns).contains("id", "status", "category", "amount");
        @SuppressWarnings("unchecked")
        Map<String, Object> pss = (Map<String, Object>) r.get("pg_stat_statements");
        // Default postgres:16-alpine image does NOT preload pg_stat_statements → expect available=false.
        assertThat(pss.get("available")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void timedQuerySupportsNamedParams() throws Exception {
        Map<String, Object> r = benchmarks.timed(
                "SELECT * FROM events WHERE status = :status", Map.of("status", "OK"), 200, 5);
        assertThat(((Number) r.get("elapsed_ms")).doubleValue()).isGreaterThanOrEqualTo(0.0);
        assertThat(((Number) r.get("row_count")).intValue()).isEqualTo(90);
        assertThat(r.get("truncated")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void unusedIndexesReturnsList() throws Exception {
        // Freshly-created indexes have zero scans; the helper must return them, while
        // excluding PK / unique indexes (they cannot be dropped without losing the constraint).
        @SuppressWarnings("unchecked")
        Map<String, Object> r = stats.unusedIndexes("public", null);
        assertThat(r.get("supported")).isEqualTo(Boolean.TRUE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> indexes = (List<Map<String, Object>>) r.get("indexes");
        // The PK (customers_pkey) must NOT be in the list.
        assertThat(indexes).noneMatch(e -> "customers_pkey".equals(e.get("index")));
        // Our non-unique idx_customers_name is a candidate.
        assertThat(indexes).anyMatch(e -> "idx_customers_name".equals(e.get("index")));
    }
}
