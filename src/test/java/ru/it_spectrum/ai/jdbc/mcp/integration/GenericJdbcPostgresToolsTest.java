package ru.it_spectrum.ai.jdbc.mcp.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Generic JDBC against a database that has schemas: PostgreSQL with {@code "dialect": "generic"}
 * and its bundled driver. Covers what the SQLite test cannot — real schema names, system schemas to
 * hide, lower-case identifier folding, routines listed through {@code DatabaseMetaData}.
 */
@Tag("integration")
class GenericJdbcPostgresToolsTest extends AbstractToolsIntegrationTest {

    private static final String CONNECTION_NAME = "pg-generic";

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

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
            PG.start();
            seed();
            JdbcProperties properties = new JdbcProperties(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword(),
                    null, 30, 1000, 100, "strict", 2, 0, 10_000, 5_000, 60_000);
            DriverProperties driver = new DriverProperties("generic", null, null);
            dataSource = DataSourceConfig.createDataSource(properties,
                    DatabaseKind.resolve(properties.url(), driver.dialect(), false), driver, CONNECTION_NAME);
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
                    "public",
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

    private static void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id INT PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT REFERENCES customers(id), "
                    + "total NUMERIC(10,2))");
            statement.execute("CREATE INDEX idx_orders_customer ON orders(customer_id)");
            statement.execute("CREATE SCHEMA sales");
            statement.execute("CREATE TABLE sales.regions (id INT PRIMARY KEY, name TEXT)");
            statement.execute("CREATE FUNCTION customer_count() RETURNS BIGINT LANGUAGE sql "
                    + "AS 'SELECT COUNT(*) FROM customers'");
            statement.execute("INSERT INTO customers VALUES (1, 'Alice'), (2, 'Bob')");
            statement.execute("INSERT INTO orders VALUES (1, 1, 10.5), (2, 1, 20.0), (3, 2, 5.0)");
        }
    }

    @Test
    void realSchemasAreListedWithoutSystemOnes() {
        assertThat(textValues(array(metadataTools().listSchemas(connection(), false).schemas())))
                .contains("public", "sales")
                .doesNotContain("pg_catalog", "information_schema");

        ArrayNode sales = array(metadataTools().listTables(connection(), "sales", "%", null).tables());
        assertThat(findByField(sales, "name", "regions")).isNotNull();
        assertThat(findByField(sales, "name", "customers")).isNull();
    }

    @Test
    void describesAndQueriesWithFoldedIdentifiers() {
        ObjectNode orders = object(metadataTools().describeTable(connection(), "public", "orders"));
        ObjectNode fk = (ObjectNode) ((ArrayNode) field(orders, "foreignKeys")).get(0);
        assertThat(field(fk, "referencedSchema").asText()).isEqualTo("public");
        assertThat(field(fk, "referencedTable").asText()).isEqualTo("customers");

        ObjectNode sample = object(sampleTools().sampleRows(connection(), "public", "orders", 2));
        assertThat(field(sample, "rowCount").asInt()).isEqualTo(2);
        ObjectNode upper = object(sampleTools().sampleRows(connection(), "public", "ORDERS", 1));
        assertThat(field(upper, "rowCount").asInt()).isEqualTo(1);

        ObjectNode histogram = object(distributionTools().columnHistogram(connection(), "public", "orders", "total"));
        assertThat(field(histogram, "p50").asDouble()).isCloseTo(10.5, within(1e-9));
    }

    @Test
    void routinesComeFromDatabaseMetaData() {
        ArrayNode routines = array(metadataTools().listRoutines(connection(), "public", "customer%").routines());
        ObjectNode routine = (ObjectNode) findByField(routines, "name", "customer_count");
        assertThat(routine).isNotNull();
        assertUnsupported(() -> metadataTools().getRoutineDefinition(connection(), "public", "customer_count"),
                "routine sources");
    }

    @Test
    void statsCountRowsAndReadIndexesFromMetadata() {
        ObjectNode table = object(statsTools().tableStats(connection(), "public", "orders"));
        assertThat(field(table, "estimatedRows").asLong()).isEqualTo(3L);

        ArrayNode indexes = (ArrayNode) field(object(statsTools().indexStats(connection(), "public", "orders")), "indexes");
        ObjectNode pk = (ObjectNode) findByField(indexes, "indexName", "orders_pkey");
        assertThat(pk).isNotNull();
        assertThat(field(pk, "isPrimary").asBoolean()).isTrue();

        ObjectNode coverage = object(statsTools().fkIndexCoverage(connection(), "public", "orders"));
        assertThat(field(coverage, "uncoveredCount").asInt()).isZero();
    }
}
