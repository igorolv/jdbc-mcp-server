package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.it_spectrum.ai.jdbc.mcp.config.DataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.dialect.PostgresDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaSnapshotCache;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.plan.PostgresPlanParser;
import ru.it_spectrum.ai.jdbc.mcp.sql.BenchmarkService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryLineageService;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryLintService;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.tools.BenchmarkTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.DistributionTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.tools.MetadataTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.QueryTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SampleTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SchemaContextTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SnapshotTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.StatsTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.ToolErrors;
import ru.it_spectrum.ai.jdbc.mcp.usage.ProceduralSqlExtractor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;

abstract class AbstractPostgresToolsIntegrationTest extends AbstractToolsIntegrationTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jdbcmcp")
            .withUsername("jdbcmcp")
            .withPassword("jdbcmcp");

    private static final IntegrationTestContext CONTEXT = createContext();

    @Override
    protected final IntegrationTestContext context() {
        return CONTEXT;
    }

    private static IntegrationTestContext createContext() {
        try {
            PG.start();
            seedDatabase();

            JdbcProperties properties = new JdbcProperties(
                    PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(),
                    "public", 10, 1000, 100, "strict", 40, 1, 10_000, 5_000, 60_000, 300, 2000);
            DataSource dataSource = buildPool(properties);
            SqlDialect dialect = new PostgresDialect();
            ReadOnlyGuard guard = new ReadOnlyGuard(properties);
            SqlExecutor executor = new SqlExecutor(dataSource, dialect, properties, guard);
            SchemaSnapshotCache cache = new SchemaSnapshotCache(properties);
            MetadataService metadata = new MetadataService(executor, dialect, properties, cache);
            StatsService stats = new StatsService(executor, dialect, properties);
            SchemaContextService schemaContext = new SchemaContextService(metadata, stats, executor, dialect, null);
            ObjectMapper mapper = new JsonConfig().jdbcMcpObjectMapper();
            PostgresPlanParser planParser = new PostgresPlanParser(mapper);
            DistributionService distribution = new DistributionService(
                    executor, dialect, properties, planParser);
            BenchmarkService benchmarks = new BenchmarkService(executor, dialect);
            QueryAnalysisService analysis = new QueryAnalysisService();
            QueryLineageService lineage = new QueryLineageService(analysis, metadata, new ProceduralSqlExtractor());
            QueryLintService lint = new QueryLintService(analysis, metadata, stats);
            JsonResponses json = new JsonResponses(mapper);
            ToolErrors errors = new ToolErrors(json);

            return new IntegrationTestContext(
                    properties.defaultSchema(),
                    new QueryTools(executor, dialect, properties, guard, planParser, analysis, lineage, lint, json, errors),
                    new MetadataTools(metadata, json, errors),
                    new SampleTools(executor, dialect, json, errors),
                    new StatsTools(stats, json, errors),
                    new SchemaContextTools(schemaContext, json, errors),
                    new DistributionTools(distribution, json, errors),
                    new BenchmarkTools(benchmarks, json, errors),
                    new SnapshotTools(metadata, cache, json, errors)
            );
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id SERIAL PRIMARY KEY, name TEXT NOT NULL, email TEXT UNIQUE)");
            statement.execute("CREATE INDEX idx_customers_name ON customers(name)");
            statement.execute("CREATE TABLE orders (" +
                    "id SERIAL PRIMARY KEY, " +
                    "customer_id INT REFERENCES customers(id), " +
                    "total NUMERIC(10,2), " +
                    "CONSTRAINT orders_total_nonnegative CHECK (total >= 0))");
            statement.execute("CREATE TABLE line_items (id SERIAL PRIMARY KEY, order_id INT REFERENCES orders(id), sku TEXT, qty INT)");
            statement.execute("CREATE INDEX idx_li_order_sku ON line_items(order_id, sku)");
            statement.execute("CREATE INDEX idx_li_order ON line_items(order_id)");
            statement.execute("CREATE TABLE customer_notes (id SERIAL PRIMARY KEY, customer_id INT NOT NULL, note TEXT)");
            statement.execute("CREATE INDEX idx_customer_notes_note ON customer_notes(note)");
            statement.execute("CREATE TABLE events (" +
                    "id SERIAL PRIMARY KEY, " +
                    "status TEXT NOT NULL, " +
                    "category TEXT, " +
                    "amount NUMERIC(10,2), " +
                    "created_at TIMESTAMP DEFAULT NOW(), " +
                    "CONSTRAINT events_status_check CHECK (status IN ('OK', 'FAIL')))");
            statement.execute("CREATE SEQUENCE audit_seq START WITH 100 INCREMENT BY 5");
            statement.execute("""
                    CREATE OR REPLACE FUNCTION customer_count_fn()
                    RETURNS integer
                    LANGUAGE sql
                    AS $$
                    SELECT COUNT(*)::integer FROM customers
                    $$
                    """);
            statement.execute("""
                    CREATE OR REPLACE FUNCTION customer_notes_touch_fn()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                      NEW.note := COALESCE(NEW.note, '');
                      RETURN NEW;
                    END;
                    $$
                    """);
            statement.execute("CREATE TRIGGER customer_notes_touch_trg " +
                    "BEFORE INSERT OR UPDATE ON customer_notes " +
                    "FOR EACH ROW EXECUTE FUNCTION customer_notes_touch_fn()");
            statement.execute("INSERT INTO customers(name, email) VALUES ('Alice', 'a@example.com'), ('Bob', 'b@example.com')");
            statement.execute("INSERT INTO orders(customer_id, total) VALUES (1, 10.5), (1, 20.0), (2, 5.0)");
            statement.execute("INSERT INTO line_items(order_id, sku, qty) VALUES (1, 'a', 1), (1, 'b', 2), (2, 'a', 3)");
            statement.execute("INSERT INTO customer_notes(customer_id, note) VALUES (1, 'vip'), (2, 'trial')");
            statement.execute("INSERT INTO events(status, category, amount) " +
                    "SELECT 'OK', CASE WHEN g % 2 = 0 THEN 'A' ELSE 'B' END, (g * 1.5)::numeric(10,2) " +
                    "FROM generate_series(1, 90) g");
            statement.execute("INSERT INTO events(status, category, amount) " +
                    "SELECT 'FAIL', NULL, (g * 0.1)::numeric(10,2) " +
                    "FROM generate_series(1, 10) g");
            statement.execute("COMMENT ON TABLE customers IS 'Customer master data'");
            statement.execute("CREATE VIEW v_customer_totals AS " +
                    "SELECT c.id, c.name, COALESCE(SUM(o.total), 0) AS total " +
                    "FROM customers c " +
                    "LEFT JOIN orders o ON o.customer_id = c.id " +
                    "GROUP BY c.id, c.name");
            statement.execute("ANALYZE customers");
            statement.execute("ANALYZE orders");
            statement.execute("ANALYZE line_items");
            statement.execute("ANALYZE customer_notes");
            statement.execute("ANALYZE events");
        }
    }

    private static DataSource buildPool(JdbcProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DataSourceConfig.applyDialectUrlTweaks(properties.url(), DatabaseKind.POSTGRESQL));
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setReadOnly(true);
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }
}
