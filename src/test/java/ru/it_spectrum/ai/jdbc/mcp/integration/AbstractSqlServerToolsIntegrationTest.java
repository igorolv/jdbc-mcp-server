package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlServerDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.PassThroughStructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.plan.SqlServerPlanParser;
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
import ru.it_spectrum.ai.jdbc.mcp.tools.StatsTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.ToolErrors;
import ru.it_spectrum.ai.jdbc.mcp.usage.ProceduralSqlExtractor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;

abstract class AbstractSqlServerToolsIntegrationTest extends AbstractToolsIntegrationTest {

    private static final MSSQLServerContainer<?> MSSQL = new MSSQLServerContainer<>(
            DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
            .acceptLicense()
            .withPassword("Str0ng_Password_123");

    private static final IntegrationTestContext CONTEXT = createContext();

    @Override
    protected final IntegrationTestContext context() {
        return CONTEXT;
    }

    private static IntegrationTestContext createContext() {
        try {
            MSSQL.start();
            seedDatabase();

            JdbcProperties properties = new JdbcProperties(
                    MSSQL.getJdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword(),
                    "dbo", 30, 1000, 100, "strict", 40, 1, 10_000, 5_000, 60_000);
            DataSource dataSource = buildPool(properties);
            SqlDialect dialect = new SqlServerDialect();
            ReadOnlyGuard guard = new ReadOnlyGuard(properties);
            SqlExecutor executor = new SqlExecutor(dataSource, dialect, properties, guard);
            PassThroughStructureSnapshotStore store = new PassThroughStructureSnapshotStore();
            MetadataService metadata = new MetadataService(executor, dialect, properties, store);
            StatsService stats = new StatsService(executor, dialect, properties);
            SchemaContextService schemaContext = new SchemaContextService(metadata, stats, executor, dialect, null);
            SqlServerPlanParser planParser = new SqlServerPlanParser();
            DistributionService distribution = new DistributionService(
                    executor, dialect, properties, planParser);
            BenchmarkService benchmarks = new BenchmarkService(executor, dialect);
            QueryAnalysisService analysis = new QueryAnalysisService();
            QueryLineageService lineage = new QueryLineageService(analysis, metadata, new ProceduralSqlExtractor());
            QueryLintService lint = new QueryLintService(analysis, metadata, stats);
            JsonResponses json = new JsonResponses(new JsonConfig().jdbcMcpObjectMapper());
            ToolErrors errors = new ToolErrors(json);

            return new IntegrationTestContext(
                    properties.defaultSchema(),
                    new QueryTools(executor, dialect, properties, guard, planParser, analysis, lineage, lint, json, errors),
                    new MetadataTools(metadata, json, errors),
                    new SampleTools(executor, dialect, json, errors),
                    new StatsTools(stats, json, errors),
                    new SchemaContextTools(schemaContext, json, errors),
                    new DistributionTools(distribution, json, errors),
                    new BenchmarkTools(benchmarks, json, errors)
            );
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MSSQL.getJdbcUrl(), MSSQL.getUsername(), MSSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE dbo.customers (" +
                    "id INT IDENTITY(1,1) PRIMARY KEY, " +
                    "name NVARCHAR(200) NOT NULL, " +
                    "email NVARCHAR(200) UNIQUE)");
            statement.execute("CREATE INDEX idx_customers_name ON dbo.customers(name)");
            statement.execute("CREATE TABLE dbo.orders (" +
                    "id INT IDENTITY(1,1) PRIMARY KEY, " +
                    "customer_id INT REFERENCES dbo.customers(id), " +
                    "total DECIMAL(10,2), " +
                    "CONSTRAINT orders_total_nonnegative CHECK (total >= 0))");
            statement.execute("CREATE TABLE dbo.line_items (" +
                    "id INT IDENTITY(1,1) PRIMARY KEY, " +
                    "order_id INT REFERENCES dbo.orders(id), " +
                    "sku NVARCHAR(100), " +
                    "qty INT)");
            statement.execute("CREATE INDEX idx_li_order_sku ON dbo.line_items(order_id, sku)");
            statement.execute("CREATE INDEX idx_li_order ON dbo.line_items(order_id)");
            statement.execute("CREATE TABLE dbo.customer_notes (" +
                    "id INT IDENTITY(1,1) PRIMARY KEY, " +
                    "customer_id INT NOT NULL, " +
                    "note NVARCHAR(400))");
            statement.execute("CREATE INDEX idx_customer_notes_note ON dbo.customer_notes(note)");
            statement.execute("CREATE TABLE dbo.events (" +
                    "id INT IDENTITY(1,1) PRIMARY KEY, " +
                    "status NVARCHAR(20) NOT NULL, " +
                    "category NVARCHAR(20), " +
                    "amount DECIMAL(10,2), " +
                    "created_at DATETIME2 DEFAULT SYSUTCDATETIME(), " +
                    "CONSTRAINT events_status_check CHECK (status IN ('OK', 'FAIL')))");
            statement.execute("CREATE SEQUENCE dbo.audit_seq START WITH 100 INCREMENT BY 5");
            statement.execute("""
                    CREATE PROCEDURE dbo.customer_count_fn
                    AS
                    BEGIN
                      SET NOCOUNT ON;
                      SELECT COUNT(*) AS c FROM dbo.customers;
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER dbo.customer_notes_touch_trg
                    ON dbo.customer_notes
                    AFTER INSERT, UPDATE
                    AS
                    BEGIN
                      SET NOCOUNT ON;
                    END
                    """);
            statement.execute("INSERT INTO dbo.customers(name, email) VALUES " +
                    "('Alice', 'a@example.com'), ('Bob', 'b@example.com')");
            statement.execute("INSERT INTO dbo.orders(customer_id, total) VALUES " +
                    "(1, 10.5), (1, 20.0), (2, 5.0)");
            statement.execute("INSERT INTO dbo.line_items(order_id, sku, qty) VALUES " +
                    "(1, 'a', 1), (1, 'b', 2), (2, 'a', 3)");
            statement.execute("INSERT INTO dbo.customer_notes(customer_id, note) VALUES " +
                    "(1, 'vip'), (2, 'trial')");
            statement.execute("""
                    WITH n AS (
                        SELECT TOP (90) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS g
                        FROM sys.all_objects
                    )
                    INSERT INTO dbo.events(status, category, amount)
                    SELECT 'OK',
                           CASE WHEN g % 2 = 0 THEN 'A' ELSE 'B' END,
                           CAST(g * 1.5 AS DECIMAL(10,2))
                    FROM n
                    """);
            statement.execute("""
                    WITH n AS (
                        SELECT TOP (10) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS g
                        FROM sys.all_objects
                    )
                    INSERT INTO dbo.events(status, category, amount)
                    SELECT 'FAIL', NULL, CAST(g * 0.1 AS DECIMAL(10,2))
                    FROM n
                    """);
            statement.execute("""
                    EXEC sys.sp_addextendedproperty
                      @name=N'MS_Description',
                      @value=N'Customer master data',
                      @level0type=N'SCHEMA', @level0name=N'dbo',
                      @level1type=N'TABLE',  @level1name=N'customers'
                    """);
            statement.execute("CREATE VIEW dbo.v_customer_totals AS " +
                    "SELECT c.id, c.name, COALESCE(SUM(o.total), 0) AS total " +
                    "FROM dbo.customers c " +
                    "LEFT JOIN dbo.orders o ON o.customer_id = c.id " +
                    "GROUP BY c.id, c.name");
            statement.execute("UPDATE STATISTICS dbo.customers");
            statement.execute("UPDATE STATISTICS dbo.orders");
            statement.execute("UPDATE STATISTICS dbo.line_items");
            statement.execute("UPDATE STATISTICS dbo.customer_notes");
            statement.execute("UPDATE STATISTICS dbo.events");
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
