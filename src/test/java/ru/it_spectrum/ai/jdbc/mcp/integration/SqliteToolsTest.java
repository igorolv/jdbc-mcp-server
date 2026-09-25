package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import ru.it_spectrum.ai.jdbc.mcp.plan.SqlitePlanParser;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The SQLite dialect end to end over the bundled driver, as a plain {@code jdbc:sqlite:} URL is
 * served: the pool comes from {@link DataSourceConfig}, which adds {@code open_mode=1}. No Docker.
 */
class SqliteToolsTest extends AbstractToolsIntegrationTest {

    private static final String CONNECTION_NAME = "shop";

    private static HikariDataSource dataSource;
    private static final IntegrationTestContext CONTEXT = createContext();

    @Override
    protected IntegrationTestContext context() {
        return CONTEXT;
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) dataSource.close();
    }

    private static IntegrationTestContext createContext() {
        try {
            Path db = Files.createTempDirectory("sqlite-dialect").resolve("shop.db");
            seed(db);
            JdbcProperties properties = new JdbcProperties(
                    "jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/'),
                    null, null, null, 30, 1000, 100, "strict", 2, 0, 10_000, 5_000, 60_000);
            DatabaseKind kind = DatabaseKind.fromUrl(properties.url());
            dataSource = DataSourceConfig.createDataSource(properties, kind, CONNECTION_NAME);
            SqlDialect dialect = SqlDialect.forKind(kind);
            ReadOnlyGuard guard = new ReadOnlyGuard(properties);
            SqlExecutor executor = new SqlExecutor(dataSource, dialect, properties, guard);
            PassThroughStructureSnapshotStore store = new PassThroughStructureSnapshotStore();
            MetadataService metadata = new MetadataService(executor, dialect, properties, store);
            StatsService stats = new StatsService(executor, dialect, properties);
            SchemaContextService schemaContext = new SchemaContextService(metadata, stats, executor, dialect, null);
            SqlitePlanParser planParser = new SqlitePlanParser();
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
                    "main",
                    new QueryTools(connections, errors),
                    new QueryAnalysisTools(connections, errors),
                    new MetadataTools(connections, json, errors),
                    new SampleTools(connections, json, errors),
                    new StatsTools(connections, json, errors),
                    new SchemaContextTools(connections, json, errors),
                    new DistributionTools(connections, json, errors),
                    new BenchmarkTools(connections, json, errors));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void seed(Path db) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT NOT NULL, email TEXT UNIQUE)");
            statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "customer_id INTEGER REFERENCES customers, total REAL CHECK (total >= 0))");
            statement.execute("CREATE INDEX idx_orders_customer ON orders(customer_id)");
            statement.execute("CREATE TABLE line_items (order_id INTEGER NOT NULL, sku TEXT NOT NULL, qty INTEGER, "
                    + "PRIMARY KEY (order_id, sku), FOREIGN KEY (order_id) REFERENCES orders(id))");
            statement.execute("CREATE INDEX idx_li_order_sku ON line_items(order_id, sku)");
            statement.execute("CREATE INDEX idx_li_order ON line_items(order_id)");
            statement.execute("CREATE TABLE events (id INTEGER PRIMARY KEY, status TEXT NOT NULL, category TEXT, amount REAL)");
            statement.execute("CREATE TABLE audit (note TEXT)");
            statement.execute("CREATE VIEW v_customer_totals AS SELECT c.id, c.name, SUM(o.total) AS total "
                    + "FROM customers c LEFT JOIN orders o ON o.customer_id = c.id GROUP BY c.id, c.name");
            statement.execute("CREATE TRIGGER trg_orders_audit INSERT ON orders BEGIN INSERT INTO audit VALUES ('new'); END");
            statement.execute("CREATE TRIGGER trg_customers_rename AFTER UPDATE OF name ON customers "
                    + "BEGIN INSERT INTO audit VALUES ('rename'); END");
            statement.execute("INSERT INTO customers(id, name, email) VALUES (1, 'Alice', 'a@example.com'), "
                    + "(2, 'Боб', 'b@example.com')");
            statement.execute("INSERT INTO orders(customer_id, total) VALUES (1, 10.5), (1, 20.0), (2, 5.0)");
            statement.execute("INSERT INTO line_items VALUES (1, 'a', 1), (1, 'b', 2), (2, 'a', 3)");
            statement.execute("""
                    WITH RECURSIVE g(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < 100)
                    INSERT INTO events(id, status, category, amount)
                    SELECT n,
                           CASE WHEN n <= 90 THEN 'OK' ELSE 'FAIL' END,
                           CASE WHEN n > 90 THEN NULL WHEN n % 2 = 0 THEN 'A' ELSE 'B' END,
                           CASE WHEN n <= 90 THEN n * 1.5 ELSE (n - 90) * 0.1 END
                    FROM g
                    """);
        }
    }

    // ---------------- structure ----------------

    @Test
    void theDatabaseIsSchemaMain() {
        assertThat(textValues(array(metadataTools().listSchemas(connection(), false).schemas())))
                .containsExactly("main");
        ArrayNode tables = array(metadataTools().listTables(connection(), "main", "%", null).tables());
        assertThat(field(findByField(tables, "name", "orders"), "schema").asText()).isEqualTo("main");
        assertInvalidArgument(() -> metadataTools().listTables(connection(), "temp", "%", null), "no schemas");

        ObjectNode qualified = object(queryTools().executeQuery(connection(),
                "SELECT COUNT(*) AS n FROM main.orders", null, null, null, 5));
        assertThat(field(row(qualified, 0), "n").asInt()).isEqualTo(3);
    }

    @Test
    void describesKeysFromPragmas() {
        ObjectNode items = object(metadataTools().describeTable(connection(), null, "line_items"));
        assertThat(textValues((ArrayNode) field(field(items, "primaryKey"), "columns"))).containsExactly("order_id", "sku");
        ObjectNode fk = (ObjectNode) ((ArrayNode) field(items, "foreignKeys")).get(0);
        assertThat(field(fk, "referencedSchema").asText()).isEqualTo("main");
        assertThat(field(fk, "referencedTable").asText()).isEqualTo("orders");

        // "REFERENCES customers" without columns points at the parent's primary key.
        ObjectNode orders = object(metadataTools().describeTable(connection(), null, "orders"));
        ObjectNode ordersFk = (ObjectNode) ((ArrayNode) field(orders, "foreignKeys")).get(0);
        assertThat(textValues((ArrayNode) field(ordersFk, "referencedColumns"))).containsExactly("id");

        ObjectNode customers = object(metadataTools().describeTable(connection(), null, "customers"));
        assertThat(field(customers, "uniqueConstraints").toString()).contains("email");
        assertThat(findByField((ArrayNode) field(customers, "referencedBy"), "fromTable", "orders")).isNotNull();
    }

    @Test
    void readsViewsTriggersAndSequencesFromSqliteSchema() {
        assertThat(metadataTools().getViewDefinition(connection(), null, "v_customer_totals"))
                .startsWith("CREATE VIEW v_customer_totals").contains("LEFT JOIN orders");

        ObjectNode orders = object(metadataTools().describeTable(connection(), null, "orders"));
        ObjectNode insertTrigger = (ObjectNode) findByField((ArrayNode) field(orders, "triggers"),
                "name", "trg_orders_audit");
        assertThat(field(insertTrigger, "timing").asText()).isEqualTo("BEFORE");
        assertThat(textValues((ArrayNode) field(insertTrigger, "events"))).containsExactly("INSERT");

        ObjectNode customers = object(metadataTools().describeTable(connection(), null, "customers"));
        ObjectNode updateTrigger = (ObjectNode) findByField((ArrayNode) field(customers, "triggers"),
                "name", "trg_customers_rename");
        assertThat(field(updateTrigger, "timing").asText()).isEqualTo("AFTER");
        assertThat(textValues((ArrayNode) field(updateTrigger, "events"))).containsExactly("UPDATE");
        assertThat(metadataTools().getTriggerDefinition(connection(), null, "customers", "trg_customers_rename"))
                .contains("AFTER UPDATE OF name ON customers");

        ArrayNode sequences = array(metadataTools().listSequences(connection(), null).sequences());
        assertThat(textValues(sequences, "name")).containsExactly("orders");

        ArrayNode search = array(metadataTools().searchObjects(connection(), "CUSTOMER").objects());
        assertThat(findByField(search, "name", "customers")).isNotNull();
        assertThat(findByField(search, "name", "v_customer_totals")).isNotNull();

        assertThat(array(metadataTools().listRoutines(connection(), null, null).routines())).isEmpty();
    }

    // ---------------- queries and plans ----------------

    @Test
    void plansComeFromExplainQueryPlan() {
        String plan = queryAnalysisTools().explainQuery(connection(),
                "SELECT o.total FROM orders o JOIN customers c ON c.id = o.customer_id WHERE c.name = :name",
                null, Map.of("name", "Alice"), false);
        assertThat(plan).startsWith("QUERY PLAN\n").contains("SCAN").contains("--");

        ObjectNode summary = object(queryAnalysisTools().analyzePlan(connection(),
                "SELECT * FROM events WHERE amount > 10", null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("sqlite");
        assertThat(field(summary, "fullScans").toString()).contains("events");
    }

    @Test
    void writesAreRejectedByTheGuardAndBySqlite() throws SQLException {
        assertRejected(() -> queryTools().executeQuery(connection(), "DELETE FROM customers", null, null, null, null),
                "Only SELECT");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM customers"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
        ObjectNode cyrillic = object(queryTools().executeQuery(connection(),
                "SELECT name FROM customers WHERE id = ?", java.util.List.of(2), null, null, 5));
        assertThat(field(row(cyrillic, 0), "name").asText()).isEqualTo("Боб");
    }

    // ---------------- statistics and distribution ----------------

    @Test
    void statsCountRowsAndMeasurePages() {
        ObjectNode table = object(statsTools().tableStats(connection(), null, "events"));
        assertThat(field(table, "estimatedRows").asLong()).isEqualTo(100L);
        assertThat(field(table, "tableSizeBytes").asLong()).isPositive();

        ArrayNode indexes = (ArrayNode) field(object(statsTools().indexStats(connection(), null, "line_items")), "indexes");
        ObjectNode composite = (ObjectNode) findByField(indexes, "indexName", "idx_li_order_sku");
        assertThat(textValues((ArrayNode) field(composite, "columns"))).containsExactly("order_id", "sku");
        assertThat(field(composite, "sizeBytes").asLong()).isPositive();

        ObjectNode redundant = object(statsTools().redundantIndexes(connection(), null, "line_items"));
        assertThat(findByField((ArrayNode) field(redundant, "findings"), "shadowedIndex", "idx_li_order")).isNotNull();
    }

    @Test
    void distributionUsesWindowFunctionsAndExactCounts() {
        ObjectNode histogram = object(distributionTools().columnHistogram(connection(), null, "events", "amount"));
        assertThat(field(histogram, "percentileFunction").asText()).isEqualTo("percentile_disc");
        assertThat(field(histogram, "p50").asDouble()).isCloseTo(60.0, within(1e-9));
        assertThat(field(histogram, "min").asDouble()).isCloseTo(0.1, within(1e-9));

        ObjectNode distribution = object(distributionTools().columnDistribution(connection(), null, "events", "status", 1));
        assertThat(((ArrayNode) field(distribution, "values"))).hasSize(1);

        ObjectNode selectivity = object(distributionTools().estimateSelectivity(connection(),
                null, "events", "status = 'FAIL'"));
        assertThat(field(selectivity, "estimatedRows").asLong()).isEqualTo(10L);
        assertThat(field(selectivity, "note").asText()).startsWith("Exact counts");
    }

    private java.util.List<String> textValues(ArrayNode array, String fieldName) {
        java.util.List<String> out = new java.util.ArrayList<>();
        array.forEach(node -> out.add(node.get(fieldName).asText()));
        return out;
    }
}
