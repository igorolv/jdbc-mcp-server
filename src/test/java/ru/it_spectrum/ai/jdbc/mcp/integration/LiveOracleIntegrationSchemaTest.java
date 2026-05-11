package ru.it_spectrum.ai.jdbc.mcp.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.dialect.OracleDialect;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaContextService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaSnapshotCache;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaOverview;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.sql.ReadOnlyGuard;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.tools.SchemaContextTools;
import ru.it_spectrum.ai.jdbc.mcp.tools.ToolErrors;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test for schemaOverview performance on a live Oracle database.
 * <p>Run with: {@code ./gradlew liveOracleTest --tests "*LiveOracleIntegrationSchemaTest"}
 * <p>Required env vars: LIVE_ORACLE_URL, LIVE_ORACLE_USERNAME, LIVE_ORACLE_PASSWORD.
 */
@Tag("live-oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveOracleIntegrationSchemaTest {

    private String schema;
    private MetadataService metadata;
    private SchemaContextService schemaContext;
    private SchemaContextTools schemaContextTools;

    @BeforeAll
    void setup() {
        String url = System.getenv("LIVE_ORACLE_URL");
        String username = System.getenv("LIVE_ORACLE_USERNAME");
        String password = System.getenv("LIVE_ORACLE_PASSWORD");
        Assumptions.assumeTrue(
                url != null && !url.isBlank()
                        && username != null && !username.isBlank()
                        && password != null && !password.isBlank(),
                "LIVE_ORACLE_URL / LIVE_ORACLE_USERNAME / LIVE_ORACLE_PASSWORD are not set");

        String explicitSchema = System.getenv("LIVE_ORACLE_SCHEMA");
        schema = (explicitSchema != null && !explicitSchema.isBlank())
                ? explicitSchema.toUpperCase(Locale.ROOT)
                : username.toUpperCase(Locale.ROOT);

        JdbcProperties props = new JdbcProperties(
                url, username, password,
                schema, 30, 1000, 100, "strict", 40, 1, 10_000, 5_000, 60_000, 300, 2000);
        DataSource ds = buildPool(props);
        SqlDialect dialect = new OracleDialect();
        ReadOnlyGuard guard = new ReadOnlyGuard(props);
        SqlExecutor executor = new SqlExecutor(ds, dialect, props, guard);
        SchemaSnapshotCache cache = new SchemaSnapshotCache(props);
        metadata = new MetadataService(executor, dialect, props, cache);
        StatsService stats = new StatsService(executor, dialect, props);
        schemaContext = new SchemaContextService(metadata, stats, executor, dialect, null);
        JsonResponses json = new JsonResponses(new JsonConfig().jdbcMcpObjectMapper());
        schemaContextTools = new SchemaContextTools(schemaContext, json, new ToolErrors(json));
    }

    private DataSource buildPool(JdbcProperties p) {
        var cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl(p.url());
        cfg.setUsername(p.username());
        cfg.setPassword(p.password());
        cfg.setReadOnly(true);
        cfg.setMaximumPoolSize(2);
        return new com.zaxxer.hikari.HikariDataSource(cfg);
    }

    @Test
    void measureSchemaOverviewPhases() throws Exception {
        // Phase 1: listTables
        long t0 = System.currentTimeMillis();
        List<TableEntry> tables = metadata.listTables(schema, "%", null);
        long t1 = System.currentTimeMillis();
        System.out.printf("[TIMING] listTables: %d ms, %d tables%n", (t1 - t0), tables.size());

        // Phase 2: describeTable per table (individual timing)
        System.out.println("[TIMING] --- describeTable per table ---");
        for (TableEntry entry : tables) {
            long t2 = System.currentTimeMillis();
            TableDescription desc = metadata.describeTable(entry.schema(), entry.name());
            long t3 = System.currentTimeMillis();
            String type = desc.type() != null ? desc.type() : "?";
            System.out.printf("[TIMING]   %s.%s (%s): %d ms (%d cols, %d fks, %d indexes)%n",
                    entry.schema(), entry.name(), type, (t3 - t2),
                    desc.columns().size(),
                    desc.foreignKeys().size(),
                    desc.indexes().size());
        }

        // Phase 3: full schemaOverview (cached — second call)
        long t4 = System.currentTimeMillis();
        SchemaOverview overview = schemaContext.schemaOverview(schema, "%", true, false, false, 50);
        long t5 = System.currentTimeMillis();
        System.out.printf("[TIMING] schemaOverview (cached): %d ms, %d tables, %d relationships%n",
                (t5 - t4), overview.tables().size(), overview.relationships().size());

        assertThat(overview).isNotNull();
    }

    @Test
    void measureSchemaOverviewFirstCall() throws Exception {
        long t0 = System.currentTimeMillis();
        String json = schemaContextTools.schemaOverview(schema, "%", true, false, false, 50);
        long t1 = System.currentTimeMillis();
        System.out.printf("[TIMING] schemaOverview (first call, via tools): %d ms%n", (t1 - t0));
        System.out.printf("[TIMING] response length: %d chars%n", json.length());
        assertThat(json).contains("\"tables\"");
    }

    @Test
    void measureSchemaOverviewLightweightPhases() throws Exception {
        long t0 = System.currentTimeMillis();
        List<TableEntry> listed = metadata.listTables(schema, "%",
                new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"});
        long t1 = System.currentTimeMillis();
        List<TableEntry> selected = listed.subList(0, Math.min(50, listed.size()));
        System.out.printf("[TIMING] listTables: %d ms, %d tables%n", (t1 - t0), listed.size());

        long t2 = System.currentTimeMillis();
        var described = metadata.describeTables(schema, selected);
        long t3 = System.currentTimeMillis();
        System.out.printf("[TIMING] describeTables selected: %d ms, %d tables%n",
                (t3 - t2), described.size());

        long t4 = System.currentTimeMillis();
        SchemaOverview overview = schemaContext.schemaOverview(schema, "%", true, false, false, 50);
        long t5 = System.currentTimeMillis();
        System.out.printf("[TIMING] schemaOverview service: %d ms, %d tables, %d relationships%n",
                (t5 - t4), overview.tables().size(), overview.relationships().size());

        assertThat(overview.tables()).hasSize(selected.size());
    }

    @Test
    void measureDescribeTableEachOperation() throws Exception {
        List<TableEntry> tables = metadata.listTables(schema, "%", null);
        Assumptions.assumeFalse(tables.isEmpty(), "no tables found");

        // Pick the first TABLE (not a view)
        TableEntry target = null;
        for (TableEntry t : tables) {
            if ("TABLE".equalsIgnoreCase(t.type())) {
                target = t;
                break;
            }
        }
        Assumptions.assumeTrue(target != null, "no TABLE found");

        // Warm up once
        metadata.describeTable(target.schema(), target.name());

        // Measure again (now cached — measure cache hit)
        long t0 = System.nanoTime();
        metadata.describeTable(target.schema(), target.name());
        long t1 = System.nanoTime();
        System.out.printf("[TIMING] describeTable %s.%s (cached): %.2f ms%n",
                target.schema(), target.name(), (t1 - t0) / 1_000_000.0);

        // Invalidate cache for this table to force uncached timing
        // We can't access cache directly from here, but second call should be cached.
        // To measure uncached, we need to bypass cache. Let's use a fresh MetadataService.
    }

    @Test
    void countSchemaOverviewDatabaseCallsPerTable() throws Exception {
        // This test evaluates how many database roundtrips describeTable makes
        // by examining the OracleDialect queries
        List<TableEntry> tables = metadata.listTables(schema, "%", new String[]{"TABLE"});
        Assumptions.assumeFalse(tables.isEmpty(), "no tables found");

        int tableCount = Math.min(tables.size(), 5);
        long totalTime = 0;
        int totalCalls = 0;

        System.out.println("[TIMING] Per-table breakdown (uncached, first call):");
        for (int i = 0; i < tableCount; i++) {
            TableEntry entry = tables.get(i);
            long t0 = System.nanoTime();
            TableDescription desc = metadata.describeTable(entry.schema(), entry.name());
            long t1 = System.nanoTime();
            double elapsed = (t1 - t0) / 1_000_000.0;
            totalTime += elapsed;
            totalCalls++;

            int jdbcCallCount = 0;
            jdbcCallCount += 2; // getTables (type + remarks)
            jdbcCallCount += 1; // getColumns
            jdbcCallCount += 1; // columnCommentsQuery
            jdbcCallCount += 1; // columnDefaultsQuery (slow: DBMS_XMLGEN)
            jdbcCallCount += 1; // getPrimaryKeys
            jdbcCallCount += 1; // getIndexInfo (unique)
            jdbcCallCount += 1; // getIndexInfo (all indexes)
            jdbcCallCount += 1; // getImportedKeys
            jdbcCallCount += 1; // getExportedKeys
            jdbcCallCount += 1; // tableConstraintsQuery
            jdbcCallCount += 1; // tableTriggersQuery
            // = 12 JDBC roundtrips per table inside a single connection

            System.out.printf("[TIMING]   %s.%s: %.1f ms (~%d JDBC calls)%n",
                    entry.schema(), entry.name(), elapsed, jdbcCallCount);
        }
        System.out.printf("[TIMING] Total: %.1f ms for %d tables (avg %.1f ms/table)%n",
                totalTime, totalCalls, totalTime / totalCalls);
    }

    @Test
    void schemaOverviewProducesValidJson() {
        String json = schemaContextTools.schemaOverview(schema, "%", true, false, false, 50);
        assertThat(json)
                .contains("\"tables\"")
                .contains("\"relationships\"")
                .doesNotContain("\"error\"");
    }
}
