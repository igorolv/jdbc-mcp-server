package ru.it_spectrum.ai.jdbc.mcp.metadata;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.jdbc.mcp.config.DataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.CatalogTestSupport;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A name the database does not list must never become a snapshot row: once a schema is covered, the
 * snapshot serves {@code listTables} and friends, so a persisted placeholder would pass for a real table.
 */
class UnknownTableSnapshotTest {

    @TempDir
    Path dir;

    private HikariDataSource source;
    private SqliteStructureSnapshotStore store;
    private MetadataService metadata;

    @BeforeEach
    void setUp() throws Exception {
        Path db = dir.resolve("shop.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, total REAL)");
        }
        JdbcProperties properties = new JdbcProperties(
                "jdbc:sqlite:" + db.toAbsolutePath().toString().replace('\\', '/'),
                null, null, null, 30, 1000, 100, "strict", 2, 0, 10_000, 5_000, 60_000);
        DatabaseKind kind = DatabaseKind.fromUrl(properties.url());
        source = DataSourceConfig.createDataSource(properties, kind, "shop");
        SqlDialect dialect = SqlDialect.forKind(kind);
        SqlExecutor executor = new SqlExecutor(source, dialect, properties, new ReadOnlyGuard(properties));
        store = new SqliteStructureSnapshotStore(CatalogTestSupport.temporaryCatalog(),
                new JsonConfig().jdbcMcpObjectMapper());
        metadata = new MetadataService(executor, dialect, properties, store);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.close();
    }

    @Test
    void describingAnUnknownTableAfterARebuildLeavesTheSnapshotClean() throws Exception {
        metadata.rebuildStructureSnapshot(List.of("main"));

        assertThat(metadata.describeTable("main", "nope")).isNull();

        assertThat(store.peekDescribeTable("main", "nope")).isNull();
        assertThat(metadata.listTables("main", "%", null)).extracting(TableEntry::name).containsExactly("orders");
        assertThat(store.snapshotTableNames("main", "", 10)).containsExactly("orders");
    }

    @Test
    void describeTablesSkipsUnknownNamesAndKeepsKnownOnes() throws Exception {
        var described = metadata.describeTables("main", List.of("orders", "nope"));

        assertThat(described.values()).extracting(d -> d.name()).containsExactly("orders");
        assertThat(store.peekDescribeTable("main", "nope")).isNull();
        assertThat(store.peekDescribeTable("main", "orders")).isNotNull();
    }

    @Test
    void requireTableReportsTheQualifiedMissingName() {
        assertThatThrownBy(() -> metadata.requireTable("main", "nope"))
                .isInstanceOfSatisfying(ObjectNotFoundException.class, e -> {
                    assertThat(e.objectKind()).isEqualTo("table");
                    assertThat(e.objectName()).isEqualTo("main.nope");
                });
    }
}
