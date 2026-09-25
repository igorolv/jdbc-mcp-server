package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.DataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.DriverProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.connection.TestConnections;
import ru.it_spectrum.ai.jdbc.mcp.dialect.GenericDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.PassThroughStructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Generic JDBC end to end, without Docker: an SQLite file served through a driver loaded from
 * {@code driverPath} — a copy of the sqlite-jdbc jar in a directory of its own, loaded in an
 * isolated class loader although the same driver is on the server's class path. SQLite has no
 * schemas, no plans and no catalog SQL the dialect could rely on, which is exactly what the generic
 * path has to cope with. The URL opens the file read-only ({@code open_mode=1}).
 */
class GenericJdbcSqliteToolsTest extends AbstractToolsIntegrationTest {

    private static final String CONNECTION_NAME = "legacy-sqlite";

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
            Path dir = Files.createTempDirectory("generic-sqlite");
            Path db = dir.resolve("shop.db");
            seed(db);
            Path drivers = Files.createDirectories(dir.resolve("drivers"));
            Path bundled = Path.of(org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Files.copy(bundled, drivers.resolve("sqlite-jdbc.jar"));

            JdbcProperties properties = new JdbcProperties(
                    "jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/') + "?open_mode=1",
                    null, null, null, 30, 1000, 100, "strict", 2, 0, 10_000, 5_000, 60_000);
            DriverProperties driver = new DriverProperties("generic", drivers.toString(), null);
            DatabaseKind kind = DatabaseKind.resolve(properties.url(), driver.dialect(), driver.externalDriver());
            dataSource = DataSourceConfig.createDataSource(properties, kind, driver, CONNECTION_NAME);
            SqlDialect dialect = new GenericDialect(dataSource);
            ReadOnlyGuard guard = new ReadOnlyGuard(properties);
            SqlExecutor executor = new SqlExecutor(dataSource, dialect, properties, guard);
            PassThroughStructureSnapshotStore store = new PassThroughStructureSnapshotStore();
            MetadataService metadata = new MetadataService(executor, dialect, properties, store);
            StatsService stats = new StatsService(executor, dialect, properties);
            SchemaContextService schemaContext = new SchemaContextService(metadata, stats, executor, dialect, null);
            PlanParser planParser = (result, analyzed) -> {
                throw new AssertionError("generic connections never parse plans");
            };
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
                    null,
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
            statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, "
                    + "customer_id INTEGER REFERENCES customers(id), total REAL)");
            statement.execute("CREATE TABLE composite_parent (a INTEGER, b INTEGER, PRIMARY KEY (a, b))");
            statement.execute("CREATE TABLE composite_child (x INTEGER, y INTEGER, z INTEGER, w INTEGER, "
                    + "FOREIGN KEY (x, y) REFERENCES composite_parent(a, b), "
                    + "FOREIGN KEY (z, w) REFERENCES composite_parent(a, b))");
            statement.execute("CREATE TABLE other_parent (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE shared_child (x INTEGER, "
                    + "FOREIGN KEY (x) REFERENCES customers(id), "
                    + "FOREIGN KEY (x) REFERENCES other_parent(id))");
            statement.execute("CREATE INDEX idx_orders_customer ON orders(customer_id)");
            statement.execute("CREATE TABLE events (id INTEGER PRIMARY KEY, status TEXT NOT NULL, "
                    + "category TEXT, amount REAL)");
            statement.execute("CREATE VIEW v_customer_totals AS SELECT c.id, c.name, SUM(o.total) AS total "
                    + "FROM customers c LEFT JOIN orders o ON o.customer_id = c.id GROUP BY c.id, c.name");
            statement.execute("INSERT INTO customers(id, name, email) VALUES (1, 'Alice', 'a@example.com'), "
                    + "(2, 'Боб', 'b@example.com')");
            statement.execute("INSERT INTO orders(id, customer_id, total) VALUES (1, 1, 10.5), (2, 1, 20.0), (3, 2, 5.0)");
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

    // ---------------- schemas and structure ----------------

    @Test
    void schemalessDatabaseIsOneLogicalSchema() {
        assertThat(textValues(array(metadataTools().listSchemas(connection(), false).schemas())))
                .containsExactly("PUBLIC");

        ArrayNode tables = array(metadataTools().listTables(connection(), null, "%", null).tables());
        ObjectNode customers = (ObjectNode) findByField(tables, "name", "customers");
        assertThat(customers).isNotNull();
        assertThat(field(customers, "schema").asText()).isEqualTo("PUBLIC");
        assertThat(findByField(tables, "name", "v_customer_totals")).isNotNull();
    }

    @Test
    void describesKeysAndIndexesFromDatabaseMetaData() {
        ObjectNode orders = object(metadataTools().describeTable(connection(), null, "orders"));
        assertThat(textValues((ArrayNode) field(field(orders, "primaryKey"), "columns"))).containsExactly("id");
        ObjectNode fk = (ObjectNode) ((ArrayNode) field(orders, "foreignKeys")).get(0);
        assertThat(field(fk, "referencedTable").asText()).isEqualTo("customers");
        assertThat(field(fk, "referencedSchema").asText()).isEqualTo("PUBLIC");
        assertThat(findByField((ArrayNode) field(orders, "indexes"), "name", "idx_orders_customer")).isNotNull();

        ArrayNode search = array(metadataTools().searchObjects(connection(), "CUSTOMER").objects());
        assertThat(findByField(search, "name", "customers")).isNotNull();
        assertThat(findByField(search, "name", "v_customer_totals")).isNotNull();
    }

    @Test
    void keepsUnnamedCompositeForeignKeysTogether() {
        ObjectNode child = object(metadataTools().describeTable(connection(), null, "composite_child"));
        ArrayNode foreignKeys = (ArrayNode) field(child, "foreignKeys");
        assertThat(foreignKeys).hasSize(2);
        List<List<String>> childColumns = new ArrayList<>();
        foreignKeys.forEach(fk -> {
            childColumns.add(textValues((ArrayNode) field((ObjectNode) fk, "columns")));
            assertThat(textValues((ArrayNode) field((ObjectNode) fk, "referencedColumns")))
                    .containsExactly("a", "b");
        });
        assertThat(childColumns).containsExactlyInAnyOrder(List.of("x", "y"), List.of("z", "w"));

        ObjectNode parent = object(metadataTools().describeTable(connection(), null, "composite_parent"));
        ArrayNode incoming = (ArrayNode) field(parent, "referencedBy");
        assertThat(incoming).hasSize(2);
        List<List<String>> incomingColumns = new ArrayList<>();
        incoming.forEach(fk -> incomingColumns.add(textValues(
                (ArrayNode) field((ObjectNode) fk, "fromColumns"))));
        assertThat(incomingColumns).containsExactlyInAnyOrder(List.of("x", "y"), List.of("z", "w"));

        ObjectNode coverage = object(statsTools().fkIndexCoverage(connection(), null, "composite_child"));
        assertThat(field(coverage, "foreignKeysTotal").asInt()).isEqualTo(2);
        assertThat(field(coverage, "uncoveredCount").asInt()).isEqualTo(2);
        ArrayNode uncovered = (ArrayNode) field(coverage, "uncovered");
        List<List<String>> uncoveredColumns = new ArrayList<>();
        uncovered.forEach(fk -> uncoveredColumns.add(textValues(
                (ArrayNode) field((ObjectNode) fk, "fkColumns"))));
        assertThat(uncoveredColumns).containsExactlyInAnyOrder(List.of("x", "y"), List.of("z", "w"));

        ObjectNode shared = object(metadataTools().describeTable(connection(), null, "shared_child"));
        assertThat((ArrayNode) field(shared, "foreignKeys")).hasSize(2);
    }

    @Test
    void catalogSourcesAreUnsupported() {
        assertUnsupported(() -> metadataTools().getViewDefinition(connection(), null, "v_customer_totals"),
                "view definitions");
        assertUnsupported(() -> metadataTools().listSequences(connection(), null), "sequences");
        assertUnsupported(() -> queryAnalysisTools().explainQuery(connection(),
                "SELECT * FROM customers", null, null, false), "plans");
        assertUnsupported(() -> queryAnalysisTools().analyzePlan(connection(),
                "SELECT * FROM customers", null, null, false), "plans");
        assertThat(array(metadataTools().listRoutines(connection(), null, null).routines())).isEmpty();
    }

    // ---------------- queries ----------------

    @Test
    void executesQueriesThroughTheExternalDriver() {
        ObjectNode limited = object(queryTools().executeQuery(connection(),
                "SELECT name FROM customers ORDER BY id", null, null, 1, 5));
        assertThat(field(limited, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(limited, "truncated").asBoolean()).isTrue();

        ObjectNode named = object(queryTools().executeQuery(connection(),
                "SELECT name FROM customers WHERE id = :id", null, Map.of("id", 2), null, 5));
        assertThat(field(row(named, 0), "name").asText()).isEqualTo("Боб");

        ObjectNode sample = object(sampleTools().sampleRows(connection(), null, "events", 3));
        assertThat(field(sample, "rowCount").asInt()).isEqualTo(3);

        ObjectNode valid = object(queryAnalysisTools().validateQuery(connection(), "SELECT * FROM orders", null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
    }

    @Test
    void writesAreRejectedByTheGuardAndTheReadOnlyUrl() throws SQLException {
        assertRejected(() -> queryTools().executeQuery(connection(), "DELETE FROM customers", null, null, null, null),
                "Only SELECT");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM customers"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("readonly");
        }
    }

    @Test
    void theDriverComesFromItsOwnClassLoader() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("SQLite");
            // The server's own copy of the driver class is a different class altogether.
            assertThat(connection.isWrapperFor(org.sqlite.SQLiteConnection.class)).isFalse();
        }
    }

    // ---------------- statistics and distribution ----------------

    @Test
    void statsFallBackToMetadataAndCounts() {
        ObjectNode table = object(statsTools().tableStats(connection(), null, "events"));
        assertThat(field(table, "found").asBoolean()).isTrue();
        assertThat(field(table, "estimatedRows").asLong()).isEqualTo(100L);

        ArrayNode indexes = (ArrayNode) field(object(statsTools().indexStats(connection(), null, "orders")), "indexes");
        ObjectNode index = (ObjectNode) findByField(indexes, "indexName", "idx_orders_customer");
        assertThat(index).isNotNull();
        assertThat(textValues((ArrayNode) field(index, "columns"))).containsExactly("customer_id");

        ObjectNode unused = object(statsTools().unusedIndexes(connection(), null, null));
        assertThat(field(unused, "supported").asBoolean()).isFalse();
    }

    @Test
    void distributionUsesPortableSqlAndClientSidePercentiles() {
        ObjectNode distribution = object(distributionTools().columnDistribution(connection(), null, "events", "status", 5));
        ObjectNode ok = (ObjectNode) findByField((ArrayNode) field(distribution, "values"), "value", "OK");
        assertThat(field(ok, "frequency").asInt()).isEqualTo(90);

        ObjectNode histogram = object(distributionTools().columnHistogram(connection(), null, "events", "amount"));
        assertThat(field(histogram, "percentileFunction").asText()).isEqualTo("percentile_disc");
        assertThat(field(histogram, "totalRows").asInt()).isEqualTo(100);
        assertThat(field(histogram, "min").asDouble()).isCloseTo(0.1, within(1e-9));
        assertThat(field(histogram, "p50").asDouble()).isCloseTo(60.0, within(1e-9));
        assertThat(field(histogram, "max").asDouble()).isCloseTo(135.0, within(1e-9));

        ObjectNode selectivity = object(distributionTools().estimateSelectivity(connection(),
                null, "events", "status = 'FAIL'"));
        assertThat(field(selectivity, "estimatedRows").asLong()).isEqualTo(10L);
        assertThat(field(selectivity, "note").asText()).startsWith("Exact counts");

        ObjectNode join = object(distributionTools().joinCardinality(connection(),
                null, "customers", "id", null, "orders", "customer_id", "INNER"));
        assertThat(field(join, "estimatedRows").asLong()).isEqualTo(3L);
    }

    @Test
    void schemaContextFollowsForeignKeys() {
        ObjectNode paths = object(schemaContextTools().findJoinPaths(connection(),
                null, "orders", null, "customers", null, null, null, false));
        assertThat(((ArrayNode) field(paths, "paths")).size()).isGreaterThan(0);
    }
}
