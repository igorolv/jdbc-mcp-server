package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageDataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageConfidence;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageFieldUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutput;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutputColumn;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageTransformation;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageTransformationKind;

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
    void declaredFkEdgeGetsDeclaredSchemaEvidenceEvenWithoutCatalogData() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        Map<String, Object> evidence = evidence(edges.get(0));
        assertThat(evidence).containsOnlyKeys("declaredSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> declared = (Map<String, Object>) evidence.get("declaredSchema");
        assertThat(declared.get("foreignKeyName")).isEqualTo("foreignKey_ORDERS_CUSTOMER_ID");
        assertThat(declared.get("fromColumns")).isEqualTo(List.of("CUSTOMER_ID"));
        assertThat(declared.get("toColumns")).isEqualTo(List.of("ID"));
    }

    @Test
    void declaredFkEdgeGetsObservedQueryLayerWhenMatchingPairsExist() {
        List<QueryUsage> usages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            usages.add(simple("SHOP", "obs" + i + ".sql",
                    "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        }
        usageCatalog.rebuild(usages);
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        Map<String, Object> evidence = evidence(edges.get(0));
        assertThat(evidence).containsKeys("declaredSchema", "observedQuery");
        @SuppressWarnings("unchecked")
        Map<String, Object> observed = (Map<String, Object>) evidence.get("observedQuery");
        assertThat(observed.get("joinSupport")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<String> uids = (List<String>) observed.get("queryUids");
        assertThat(uids).hasSize(3);
    }

    @Test
    void declaredFkEdgeGetsSemanticUsageLayerFromSharedBusinessTerms() {
        usageCatalog.rebuild(List.of(
                richQuery("SHOP", "InvoiceReport.json", "header",
                        "Invoice payer header", "Customers",
                        "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                        "payer_name", "Payer name", "Invoice payer"),
                richQuery("SHOP", "ShipmentReport.json", "main",
                        "Customer shipments", "Customers",
                        "SELECT o.id AS order_id FROM orders o JOIN customers c ON o.customer_id = c.id",
                        "order_id", "Order id", "Invoice payer")));
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        Map<String, Object> evidence = evidence(edges.get(0));
        assertThat(evidence).containsKeys("declaredSchema", "observedQuery", "semanticUsage");
        @SuppressWarnings("unchecked")
        Map<String, Object> semantic = (Map<String, Object>) evidence.get("semanticUsage");
        assertThat(semantic.get("coOccurringQueryCount")).isEqualTo(2);
        assertThat(termValues(semantic, "sharedBusinessDomains")).contains("Customers");
        assertThat(termValues(semantic, "sharedBusinessObjects")).contains("Invoice payer");
        assertThat(termValues(semantic, "sharedOutputLabels")).contains("Payer name", "Order id");
    }

    @Test
    void appendsObservedOnlyEdgeWithObservedQueryLayer() {
        usageCatalog.rebuild(List.of(simple("SHOP", "q.sql",
                "SELECT * FROM events e JOIN sessions s ON e.session_id = s.id")));

        List<Map<String, Object>> edges = new ArrayList<>();
        probe.run(edges, Set.of("EVENTS", "SESSIONS"), true);

        assertThat(edges).singleElement()
                .satisfies(e -> {
                    assertThat(e.get("relationshipType")).isEqualTo("observed");
                    assertThat(e.get("undirected")).isEqualTo(true);
                    assertThat(e.get("fromTable")).isEqualTo("EVENTS");
                    assertThat(e.get("toTable")).isEqualTo("SESSIONS");
                    Map<String, Object> evidence = evidence(e);
                    assertThat(evidence).doesNotContainKey("declaredSchema");
                    assertThat(evidence).containsKey("observedQuery");
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
    void includeObservedFalseSkipsCatalogEntirelyButStillStampsDeclaredSchema() {
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
        Map<String, Object> evidence = evidence(edges.get(0));
        assertThat(evidence).containsOnlyKeys("declaredSchema");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Map<String, Object> edge) {
        Object raw = edge.get("evidence");
        assertThat(raw).as("edge evidence bundle").isInstanceOf(Map.class);
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> termValues(Map<String, Object> semantic, String key) {
        return ((List<Map<String, Object>>) semantic.get(key)).stream()
                .map(row -> row.get("value"))
                .toList();
    }

    private static QueryUsage simple(String dataSource, String path, String sql) {
        return new QueryUsage(
                dataSource,
                new QueryUsageSource("dao", path, null),
                null, null, null, sql,
                null, null, null, null);
    }

    private static QueryUsage richQuery(String dataSource, String path, String unit,
                                        String label, String domain, String sql,
                                        String alias, String outputLabel, String businessObject) {
        return new QueryUsage(
                dataSource,
                new QueryUsageSource("report", path, unit),
                label, domain, List.of(),
                sql,
                null,
                List.of(new QueryUsageOutput(alias, alias, outputLabel, null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage(
                        alias,
                        businessObject,
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null,
                        null,
                        QueryUsageConfidence.HIGH)),
                null);
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
