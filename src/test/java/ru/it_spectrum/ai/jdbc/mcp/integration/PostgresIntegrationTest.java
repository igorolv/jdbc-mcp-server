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
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
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
        SqlDialect dialect = new PostgresDialect();
        ReadOnlyGuard guard = new ReadOnlyGuard(props);
        executor = new SqlExecutor(ds, dialect, props, guard);
        metadata = new MetadataService(executor, dialect, props);
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
}
