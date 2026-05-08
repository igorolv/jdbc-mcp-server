package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageDataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeDecorationTest {

    private UsageCatalogService usageCatalog;
    private DecorationProbe probe;

    @BeforeEach
    void setUp() throws Exception {
        UsageProperties properties = new UsageProperties(true, List.of(), false, false, false, "");
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties);
        usageCatalog = new UsageCatalogService(properties, ds, new QueryAnalysisService());
        probe = new DecorationProbe(usageCatalog);
    }

    @Test
    void decoratesDeclaredFkEdgesWithEvidenceLevelEvenWithoutCatalogData() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        assertThat(edges.get(0).get("evidenceLevel")).isEqualTo("declared_fk");
        assertThat(edges.get(0).get("observedSupport")).isNull();
    }

    @Test
    void decoratesEdgesWithObservedSupportWhenMatchingPairsExist() {
        List<QueryUsage> usages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            usages.add(simple("SHOP", "obs" + i + ".sql",
                    "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        }
        usageCatalog.rebuild(usages);
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        assertThat(edges.get(0).get("evidenceLevel")).isEqualTo("declared_fk");
        assertThat(edges.get(0).get("observedSupport")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<String> uids = (List<String>) edges.get(0).get("observedQueries");
        assertThat(uids).hasSize(3);
    }

    @Test
    void appendsObservedOnlyEdgeBetweenDescribedTables() {
        usageCatalog.rebuild(List.of(simple("SHOP", "q.sql",
                "SELECT * FROM events e JOIN sessions s ON e.session_id = s.id")));

        List<Map<String, Object>> edges = new ArrayList<>();
        probe.run(edges, Set.of("EVENTS", "SESSIONS"), true);

        assertThat(edges).singleElement()
                .satisfies(e -> {
                    assertThat(e.get("relationshipType")).isEqualTo("observed");
                    assertThat(e.get("evidenceLevel")).isEqualTo("observed_in_queries");
                    assertThat(e.get("undirected")).isEqualTo(true);
                    assertThat(e.get("fromTable")).isEqualTo("EVENTS");
                    assertThat(e.get("toTable")).isEqualTo("SESSIONS");
                });
    }

    @Test
    void doesNotAppendObservedEdgeWhenOtherSideIsOutOfScope() {
        usageCatalog.rebuild(List.of(simple("SHOP", "q.sql",
                "SELECT * FROM events e JOIN sessions s ON e.session_id = s.id")));

        List<Map<String, Object>> edges = new ArrayList<>();
        probe.run(edges, Set.of("EVENTS"), true); // sessions is not in described scope

        assertThat(edges).isEmpty();
    }

    @Test
    void includeObservedFalseSkipsCatalogEntirelyButStillStampsEvidenceLevel() {
        List<QueryUsage> usages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            usages.add(simple("SHOP", "obs" + i + ".sql",
                    "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        }
        usageCatalog.rebuild(usages);
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), false);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).get("evidenceLevel")).isEqualTo("declared_fk");
        assertThat(edges.get(0).get("observedSupport")).isNull();
    }

    // ---------------------------------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------------------------------

    private static Map<String, Object> edge(String type, String fromTable, String fromColumn,
                                            String toTable, String toColumn) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("relationshipType", type);
        e.put("fkName", type + "_" + fromTable + "_" + fromColumn);
        e.put("fromSchema", "APP");
        e.put("fromTable", fromTable);
        e.put("fromColumns", List.of(fromColumn));
        e.put("toSchema", "APP");
        e.put("toTable", toTable);
        e.put("toColumns", List.of(toColumn));
        return e;
    }

    private static QueryUsage simple(String dataSource, String path, String sql) {
        return new QueryUsage(
                dataSource,
                new QueryUsageSource("dao", path, null),
                null, null, null, sql,
                null, null, null, null);
    }

    /**
     * Minimal SchemaContextSupport subclass that only exists to expose the
     * package-protected {@code decorateAndAppendObserved}. Avoids spinning up the rest of
     * the metadata stack just to test edge decoration.
     */
    private static final class DecorationProbe extends SchemaContextSupport {
        DecorationProbe(UsageCatalogService usageCatalog) {
            super(null, null, null, null, usageCatalog);
        }

        void run(List<Map<String, Object>> edges, Set<String> describedNamesUpper,
                 boolean includeObserved) {
            decorateAndAppendObserved(edges, describedNamesUpper, includeObserved);
        }
    }
}
