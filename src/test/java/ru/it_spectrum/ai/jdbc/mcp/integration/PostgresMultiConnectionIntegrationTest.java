package ru.it_spectrum.ai.jdbc.mcp.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionDefinition;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionsLoader;
import ru.it_spectrum.ai.jdbc.mcp.connection.SpringConnectionContextFactory;
import ru.it_spectrum.ai.jdbc.mcp.model.admin.RebuildCatalogResult;
import ru.it_spectrum.ai.jdbc.mcp.model.connection.ConnectionInfo;
import ru.it_spectrum.ai.jdbc.mcp.model.connection.ListConnectionsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.tools.AdminTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.ConnectionTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.tools.MetadataTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.QueryTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.ToolErrors;
import ru.it_spectrum.ai.jdbc.mcp.tools.UsageTools;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end check of the multi-connection path: one process, one set of tools, two live
 * PostgreSQL databases described by a real {@code connections.json}, plus one connection that
 * points nowhere.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresMultiConnectionIntegrationTest {

    private static final PostgreSQLContainer<?> ORDERS = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("orders").withUsername("orders").withPassword("orders");

    private static final PostgreSQLContainer<?> BILLING = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("billing").withUsername("billing").withPassword("billing");

    /** Nothing listens here: the third connection must fail on its own calls only. */
    private static final String UNREACHABLE_URL = "jdbc:postgresql://127.0.0.1:1/absent";

    private static Path dataDir;
    private static AnnotationConfigApplicationContext parent;
    private static ConnectionRegistry connections;
    private static QueryTools queryTools;
    private static MetadataTools metadataTools;
    private static AdminTools adminTools;
    private static UsageTools usageTools;
    private static ConnectionTools connectionTools;

    @BeforeAll
    void startContainers() throws Exception {
        ORDERS.start();
        BILLING.start();
        seed(ORDERS, "CREATE TABLE orders_only (id SERIAL PRIMARY KEY, sku TEXT NOT NULL)",
                "INSERT INTO orders_only(sku) VALUES ('a'), ('b'), ('c')");
        seed(BILLING, "CREATE TABLE billing_only (id SERIAL PRIMARY KEY, invoice TEXT NOT NULL)",
                "INSERT INTO billing_only(invoice) VALUES ('i-1')");

        dataDir = Files.createTempDirectory("jdbc-mcp-multi-connection");
        Files.writeString(dataDir.resolve("connections.json"), """
                {
                  "connections": {
                    "orders": {
                      "url": "%s",
                      "username": "orders",
                      "password": "${ORDERS_PASSWORD}",
                      "defaultSchema": "public",
                      "description": "Order service"
                    },
                    "billing": {
                      "url": "%s",
                      "username": "billing",
                      "password": "billing",
                      "defaultSchema": "public"
                    },
                    "down": {
                      "url": "%s",
                      "username": "u",
                      "password": "p",
                      "poolConnectionTimeoutMs": 500
                    }
                  }
                }
                """.formatted(ORDERS.getJdbcUrl(), BILLING.getJdbcUrl(), UNREACHABLE_URL));

        List<ConnectionDefinition> definitions = ConnectionsLoader.load(
                new JdbcMcpProperties(dataDir.toString()),
                new JsonConfig().jdbcMcpObjectMapper(),
                Map.of("ORDERS_PASSWORD", "orders")::get);

        parent = new AnnotationConfigApplicationContext(JsonConfig.class, JsonResponses.class);
        connections = new ConnectionRegistry(definitions, new SpringConnectionContextFactory(parent));
        ObjectMapper mapper = parent.getBean(ObjectMapper.class);
        JsonResponses json = new JsonResponses(mapper);
        ToolErrors errors = new ToolErrors(json);
        queryTools = new QueryTools(connections, errors);
        metadataTools = new MetadataTools(connections, json, errors);
        adminTools = new AdminTools(connections, errors);
        usageTools = new UsageTools(connections, json, errors);
        connectionTools = new ConnectionTools(connections);
    }

    @AfterAll
    void stopContainers() {
        if (connections != null) {
            connections.close();
        }
        if (parent != null) {
            parent.close();
        }
        BILLING.stop();
        ORDERS.stop();
    }

    private static void seed(PostgreSQLContainer<?> container, String... statements) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             var statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Test
    void queriesGoToTheDatabaseNamedByTheConnectionArgument() {
        QueryResult orders = queryTools.executeQuery("orders",
                "SELECT current_database() AS db, count(*) AS n FROM orders_only",
                null, null, null, null);
        assertThat(orders.rows().getFirst()).containsEntry("db", "orders").containsEntry("n", 3L);

        QueryResult billing = queryTools.executeQuery("billing",
                "SELECT current_database() AS db, count(*) AS n FROM billing_only",
                null, null, null, null);
        assertThat(billing.rows().getFirst()).containsEntry("db", "billing").containsEntry("n", 1L);
    }

    @Test
    void metadataOfOneConnectionDoesNotLeakIntoTheOther() {
        TableDescription orders = metadataTools.describeTable("orders", "public", "orders_only");
        assertThat(orders.columns()).extracting("name").contains("sku");

        TableDescription missing = metadataTools.describeTable("billing", "public", "orders_only");
        assertThat(missing.columns()).isNullOrEmpty();

        assertThat(metadataTools.listTables("billing", "public", null, null).tables())
                .extracting("name").contains("billing_only").doesNotContain("orders_only");
    }

    @Test
    void eachConnectionKeepsItsOwnCatalogFile() {
        RebuildCatalogResult orders = adminTools.rebuildCatalog("orders", "public");
        assertThat(orders.connection()).isEqualTo("orders");
        assertThat(orders.catalogFile()).isEqualTo(
                dataDir.resolve("orders").resolve("orders.db").toAbsolutePath().toString());

        RebuildCatalogResult billing = adminTools.rebuildCatalog("billing", "public");
        assertThat(billing.connection()).isEqualTo("billing");
        assertThat(billing.catalogFile()).isEqualTo(
                dataDir.resolve("billing").resolve("billing.db").toAbsolutePath().toString());

        assertThat(snapshotTables("orders")).contains("orders_only").doesNotContain("billing_only");
        assertThat(snapshotTables("billing")).contains("billing_only").doesNotContain("orders_only");

        UsageCatalogStatus status = usageTools.usageCatalogStatus("billing");
        assertThat(status.connection()).isEqualTo("billing");
        assertThat(status.catalogEnabled()).isTrue();
    }

    @Test
    void anUnreachableConnectionFailsAloneAndListsAsUninitialisedUntilUsed() {
        ListConnectionsResult before = connectionTools.listConnections();
        assertThat(before.connections()).extracting(ConnectionInfo::name)
                .containsExactly("orders", "billing", "down");
        assertThat(byName(before, "down").initialized()).isFalse();

        assertThatThrownBy(() -> queryTools.executeQuery("down", "SELECT 1", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"kind\":\"sql\"");

        // The healthy connections are unaffected by the broken one.
        assertThat(queryTools.executeQuery("billing", "SELECT 1 AS one", null, null, null, null)
                .rows()).hasSize(1);

        assertThat(byName(connectionTools.listConnections(), "down").initialized()).isTrue();
    }

    @Test
    void unknownConnectionNamesAreRejectedWithTheAvailableOnes() {
        assertThatThrownBy(() -> queryTools.executeQuery("nope", "SELECT 1", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"kind\":\"argument\"")
                .hasMessageContaining("orders, billing, down");
    }

    @Test
    void aMissingConnectionNameIsRejectedWithTheAvailableOnes() {
        assertThatThrownBy(() -> queryTools.executeQuery(null, "SELECT 1", null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"kind\":\"argument\"")
                .hasMessageContaining("orders, billing, down");
    }

    private static ConnectionInfo byName(ListConnectionsResult result, String name) {
        return result.connections().stream()
                .filter(info -> info.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> snapshotTables(String connection) {
        try {
            return connections.resolve(connection).snapshotStore().listSnapshotTableDescriptions()
                    .stream().map(TableDescription::name).toList();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
