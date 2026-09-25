package ru.it_spectrum.ai.jdbc.mcp.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import ru.it_spectrum.ai.jdbc.mcp.config.DataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.connection.TestConnections;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.PassThroughStructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.plan.FirebirdPlanParser;
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
import ru.it_spectrum.ai.jdbc.mcp.tools.QueryAnalysisTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.QueryTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SampleTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.SchemaContextTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.StatsTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.ToolErrors;
import ru.it_spectrum.ai.jdbc.mcp.usage.ProceduralSqlExtractor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Firebird 3 over the pure-Java wire protocol — no native client needed. The database is created
 * with the WIN1251 default character set, like the legacy databases this dialect targets, and the
 * pool goes through {@link DataSourceConfig} so the production URL tweak (UTF8 connection
 * encoding) and read-only settings are what the tests exercise.
 */
abstract class AbstractFirebirdToolsIntegrationTest extends AbstractToolsIntegrationTest {

    private static final String CONNECTION_NAME = "firebird";
    private static final String PASSWORD = "masterkey";

    private static final GenericContainer<?> FIREBIRD = new GenericContainer<>(
            DockerImageName.parse("firebirdsql/firebird:3.0.14"))
            .withEnv("FIREBIRD_ROOT_PASSWORD", PASSWORD)
            .withEnv("FIREBIRD_DATABASE", "test.fdb")
            .withEnv("FIREBIRD_DATABASE_DEFAULT_CHARSET", "WIN1251")
            .withExposedPorts(3050)
            .waitingFor(Wait.forListeningPort());

    private static DataSource dataSource;

    private static final IntegrationTestContext CONTEXT = createContext();

    @Override
    protected final IntegrationTestContext context() {
        return CONTEXT;
    }

    /** The production-configured read-only pool, for tests that go below the tool layer. */
    protected static DataSource pool() {
        return dataSource;
    }

    private static String url() {
        return "jdbc:firebirdsql://" + FIREBIRD.getHost() + ":" + FIREBIRD.getMappedPort(3050)
                + "//var/lib/firebird/data/test.fdb";
    }

    private static IntegrationTestContext createContext() {
        try {
            FIREBIRD.start();
            seedDatabase();

            JdbcProperties properties = new JdbcProperties(
                    url(), "SYSDBA", PASSWORD,
                    null, 30, 1000, 100, "strict", 4, 0, 10_000, 5_000, 60_000);
            dataSource = DataSourceConfig.createDataSource(properties, DatabaseKind.FIREBIRD, CONNECTION_NAME);
            SqlDialect dialect = SqlDialect.forKind(DatabaseKind.FIREBIRD);
            ReadOnlyGuard guard = new ReadOnlyGuard(properties);
            SqlExecutor executor = new SqlExecutor(dataSource, dialect, properties, guard);
            PassThroughStructureSnapshotStore store = new PassThroughStructureSnapshotStore();
            MetadataService metadata = new MetadataService(executor, dialect, properties, store);
            StatsService stats = new StatsService(executor, dialect, properties);
            SchemaContextService schemaContext = new SchemaContextService(metadata, stats, executor, dialect, null);
            FirebirdPlanParser planParser = new FirebirdPlanParser();
            DistributionService distribution = new DistributionService(executor, dialect, properties, planParser);
            BenchmarkService benchmarks = new BenchmarkService(executor, dialect);
            QueryAnalysisService analysis = new QueryAnalysisService();
            QueryLineageService lineage = new QueryLineageService(analysis, metadata, new ProceduralSqlExtractor());
            QueryLintService lint = new QueryLintService(analysis, metadata, stats);
            JsonResponses json = new JsonResponses(new JsonConfig().jdbcMcpObjectMapper());
            ToolErrors errors = new ToolErrors(json);

            ConnectionRegistry connections = TestConnections.registry(
                    CONNECTION_NAME, properties,
                    dialect, executor, guard, planParser, store,
                    metadata, stats, schemaContext, distribution, benchmarks,
                    analysis, lineage, lint);

            return new IntegrationTestContext(
                    CONNECTION_NAME,
                    "PUBLIC",
                    new QueryTools(connections, errors),
                    new QueryAnalysisTools(connections, errors),
                    new MetadataTools(connections, json, errors),
                    new SampleTools(connections, json, errors),
                    new StatsTools(connections, json, errors),
                    new SchemaContextTools(connections, json, errors),
                    new DistributionTools(connections, json, errors),
                    new BenchmarkTools(connections, json, errors)
            );
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connectForSeeding();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "name VARCHAR(200) NOT NULL, " +
                    "email VARCHAR(200) UNIQUE)");
            statement.execute("COMMENT ON TABLE customers IS 'Customer master data'");
            statement.execute("COMMENT ON COLUMN customers.name IS 'Display name'");
            statement.execute("CREATE INDEX idx_customers_name ON customers(name)");
            statement.execute("CREATE TABLE orders (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "customer_id INTEGER, " +
                    "total DECIMAL(10,2), " +
                    "CONSTRAINT orders_customer_fk FOREIGN KEY (customer_id) REFERENCES customers(id), " +
                    "CONSTRAINT orders_total_nonnegative CHECK (total >= 0))");
            statement.execute("CREATE TABLE line_items (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "order_id INTEGER NOT NULL, " +
                    "sku VARCHAR(100), " +
                    "qty INTEGER)");
            statement.execute("CREATE INDEX idx_li_order_sku ON line_items(order_id, sku)");
            statement.execute("CREATE INDEX idx_li_order ON line_items(order_id)");
            statement.execute("CREATE TABLE customer_notes (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "customer_id INTEGER NOT NULL, " +
                    "note VARCHAR(400))");
            statement.execute("CREATE TABLE events (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "status VARCHAR(20) NOT NULL, " +
                    "category VARCHAR(20), " +
                    "amount DECIMAL(10,2), " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "CONSTRAINT events_status_check CHECK (status IN ('OK', 'FAIL')))");
            statement.execute("CREATE SEQUENCE audit_seq");
            statement.execute("""
                    CREATE PROCEDURE customer_count_proc
                    RETURNS (c INTEGER)
                    AS
                    BEGIN
                      SELECT COUNT(*) FROM customers INTO :c;
                      SUSPEND;
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER customer_notes_touch_trg FOR customer_notes
                    ACTIVE BEFORE INSERT OR UPDATE POSITION 0
                    AS
                    BEGIN
                      NEW.note = TRIM(NEW.note);
                    END
                    """);
            statement.execute("CREATE VIEW v_customer_totals AS " +
                    "SELECT c.id, c.name, COALESCE(SUM(o.total), 0) AS total " +
                    "FROM customers c " +
                    "LEFT JOIN orders o ON o.customer_id = c.id " +
                    "GROUP BY c.id, c.name");

            statement.execute("INSERT INTO customers(id, name, email) VALUES (1, 'Alice', 'a@example.com')");
            statement.execute("INSERT INTO customers(id, name, email) VALUES (2, 'Боб', 'b@example.com')");
            statement.execute("INSERT INTO orders(id, customer_id, total) VALUES (1, 1, 10.5)");
            statement.execute("INSERT INTO orders(id, customer_id, total) VALUES (2, 1, 20.0)");
            statement.execute("INSERT INTO orders(id, customer_id, total) VALUES (3, 2, 5.0)");
            statement.execute("INSERT INTO line_items(id, order_id, sku, qty) VALUES (1, 1, 'a', 1)");
            statement.execute("INSERT INTO line_items(id, order_id, sku, qty) VALUES (2, 1, 'b', 2)");
            statement.execute("INSERT INTO line_items(id, order_id, sku, qty) VALUES (3, 2, 'a', 3)");
            statement.execute("INSERT INTO customer_notes(id, customer_id, note) VALUES (1, 1, 'vip')");
            statement.execute("INSERT INTO customer_notes(id, customer_id, note) VALUES (2, 2, 'пробный')");
            statement.execute("""
                    EXECUTE BLOCK AS
                    DECLARE g INTEGER = 1;
                    BEGIN
                      WHILE (g <= 90) DO
                      BEGIN
                        INSERT INTO events(id, status, category, amount)
                        VALUES (:g, 'OK', CASE WHEN MOD(:g, 2) = 0 THEN 'A' ELSE 'B' END, :g * 1.5);
                        g = g + 1;
                      END
                      WHILE (g <= 100) DO
                      BEGIN
                        INSERT INTO events(id, status, category, amount)
                        VALUES (:g, 'FAIL', NULL, (:g - 90) * 0.1);
                        g = g + 1;
                      END
                    END
                    """);
            // Index selectivity is what Firebird's row estimates are derived from; indexes created
            // on empty tables have none until it is recomputed.
            statement.execute("""
                    EXECUTE BLOCK AS
                    DECLARE idx VARCHAR(63);
                    BEGIN
                      FOR SELECT RDB$INDEX_NAME FROM RDB$INDICES
                          WHERE COALESCE(RDB$SYSTEM_FLAG, 0) = 0 INTO :idx DO
                        EXECUTE STATEMENT 'SET STATISTICS INDEX "' || TRIM(:idx) || '"';
                    END
                    """);
        }
    }

    private static Connection connectForSeeding() throws Exception {
        SQLException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return DriverManager.getConnection(url() + "?encoding=UTF8", "SYSDBA", PASSWORD);
            } catch (SQLException e) {
                // the entrypoint creates the database while the port is already open
                last = e;
                Thread.sleep(500);
            }
        }
        throw last;
    }
}
