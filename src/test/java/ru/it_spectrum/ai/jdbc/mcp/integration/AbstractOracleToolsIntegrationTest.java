package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.oracle.OracleContainer;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.connection.TestConnections;
import ru.it_spectrum.ai.jdbc.mcp.dialect.OracleDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.PassThroughStructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.plan.OraclePlanParser;
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

abstract class AbstractOracleToolsIntegrationTest extends AbstractToolsIntegrationTest {

    private static final String CONNECTION_NAME = "oracle";

    private static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withUsername("jdbcmcp")
            .withPassword("jdbcmcp");

    private static final IntegrationTestContext CONTEXT = createContext();

    @Override
    protected final IntegrationTestContext context() {
        return CONTEXT;
    }

    private static IntegrationTestContext createContext() {
        try {
            ORACLE.start();
            seedDatabase();

            String schema = ORACLE.getUsername().toUpperCase();
            JdbcProperties properties = new JdbcProperties(
                    ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword(),
                    schema, 30, 1000, 100, "strict", 40, 1, 10_000, 5_000, 60_000);
            DataSource dataSource = buildPool(properties);
            SqlDialect dialect = new OracleDialect();
            ReadOnlyGuard guard = new ReadOnlyGuard(properties);
            SqlExecutor executor = new SqlExecutor(dataSource, dialect, properties, guard);
            PassThroughStructureSnapshotStore store = new PassThroughStructureSnapshotStore();
            MetadataService metadata = new MetadataService(executor, dialect, properties, store);
            StatsService stats = new StatsService(executor, dialect, properties);
            SchemaContextService schemaContext = new SchemaContextService(metadata, stats, executor, dialect, null);
            OraclePlanParser planParser = new OraclePlanParser();
            DistributionService distribution = new DistributionService(
                    executor, dialect, properties, planParser);
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
                    schema,
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
        try (Connection connection = DriverManager.getConnection(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR2(200) NOT NULL, email VARCHAR2(200) UNIQUE)");
            statement.execute("CREATE INDEX idx_customers_name ON customers(name)");
            statement.execute("CREATE TABLE orders (" +
                    "id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "customer_id NUMBER REFERENCES customers(id), " +
                    "total NUMBER(10,2), " +
                    "CONSTRAINT orders_total_nonnegative CHECK (total >= 0))");
            statement.execute("CREATE TABLE line_items (id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, order_id NUMBER REFERENCES orders(id), sku VARCHAR2(100), qty NUMBER)");
            statement.execute("CREATE INDEX idx_li_order_sku ON line_items(order_id, sku)");
            statement.execute("CREATE INDEX idx_li_order ON line_items(order_id)");
            statement.execute("CREATE TABLE customer_notes (id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, customer_id NUMBER NOT NULL, note VARCHAR2(400))");
            statement.execute("CREATE TABLE events (" +
                    "id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, " +
                    "status VARCHAR2(20) NOT NULL, " +
                    "category VARCHAR2(20), " +
                    "amount NUMBER(10,2), " +
                    "CONSTRAINT events_status_check CHECK (status IN ('OK', 'FAIL')))");
            statement.execute("CREATE SEQUENCE audit_seq START WITH 100 INCREMENT BY 5 NOCACHE");
            statement.execute("""
                    CREATE OR REPLACE FUNCTION customer_count_fn RETURN NUMBER IS
                      v_count NUMBER;
                    BEGIN
                      SELECT COUNT(*) INTO v_count FROM customers;
                      RETURN v_count;
                    END;
                    """);
            statement.execute("""
                    CREATE OR REPLACE TRIGGER customer_notes_touch_trg
                    BEFORE INSERT OR UPDATE ON customer_notes
                    FOR EACH ROW
                    BEGIN
                      IF :NEW.note IS NULL THEN
                        :NEW.note := '';
                      END IF;
                    END;
                    """);
            statement.execute("INSERT INTO customers(name, email) VALUES ('Alice', 'a@example.com')");
            statement.execute("INSERT INTO customers(name, email) VALUES ('Bob', 'b@example.com')");
            statement.execute("INSERT INTO orders(customer_id, total) VALUES (1, 10.5)");
            statement.execute("INSERT INTO orders(customer_id, total) VALUES (1, 20.0)");
            statement.execute("INSERT INTO orders(customer_id, total) VALUES (2, 5.0)");
            statement.execute("INSERT INTO line_items(order_id, sku, qty) VALUES (1, 'a', 1)");
            statement.execute("INSERT INTO line_items(order_id, sku, qty) VALUES (1, 'b', 2)");
            statement.execute("INSERT INTO line_items(order_id, sku, qty) VALUES (2, 'a', 3)");
            statement.execute("INSERT INTO customer_notes(customer_id, note) VALUES (1, 'vip')");
            statement.execute("INSERT INTO customer_notes(customer_id, note) VALUES (2, 'trial')");
            statement.execute("INSERT INTO events(status, category, amount) " +
                    "SELECT 'OK', CASE WHEN MOD(LEVEL, 2) = 0 THEN 'A' ELSE 'B' END, LEVEL * 1.5 " +
                    "FROM dual CONNECT BY LEVEL <= 90");
            statement.execute("INSERT INTO events(status, category, amount) " +
                    "SELECT 'FAIL', NULL, LEVEL * 0.1 " +
                    "FROM dual CONNECT BY LEVEL <= 10");
            statement.execute("COMMENT ON TABLE customers IS 'Customer master data'");
            statement.execute("CREATE OR REPLACE VIEW v_customer_totals AS " +
                    "SELECT c.id, c.name, NVL(SUM(o.total), 0) AS total " +
                    "FROM customers c " +
                    "LEFT JOIN orders o ON o.customer_id = c.id " +
                    "GROUP BY c.id, c.name");
            statement.execute("COMMIT");
            statement.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'CUSTOMERS'); END;");
            statement.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'ORDERS'); END;");
            statement.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'LINE_ITEMS'); END;");
            statement.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'CUSTOMER_NOTES'); END;");
            statement.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'EVENTS'); END;");
        }
    }

    private static DataSource buildPool(JdbcProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setReadOnly(true);
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }
}
