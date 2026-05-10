package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.model.context.RelationshipEdge;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.RelationshipEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageDataSourceConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeDecorationTest {

    private UsageCatalogService usageCatalog;
    private DecorationProbe probe;

    @BeforeEach
    void setUp() throws Exception {
        UsageProperties properties = new UsageProperties(true, List.of(), List.of(), true, true, true, 1_000);
        JdbcMcpProperties jdbcMcpProperties = new JdbcMcpProperties("");
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties, jdbcMcpProperties);
        usageCatalog = new UsageCatalogService(properties, ds, new QueryAnalysisService(),
                new JsonResponses(new JsonConfig().jdbcMcpObjectMapper()), null);
        probe = new DecorationProbe(usageCatalog);
    }

    @Test
    void declaredFkEdgeGetsDeclaredSchemaEvidenceEvenWithoutCatalogData() {
        List<RelationshipEdge> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        RelationshipEvidence evidence = evidence(edges.get(0));
        assertThat(evidence.declaredSchema()).isNotNull();
        assertThat(evidence.observedQuery()).isNull();
        assertThat(evidence.semanticUsage()).isNull();
        assertThat(evidence.declaredSchema().foreignKeyName()).isEqualTo("foreignKey_ORDERS_CUSTOMER_ID");
        assertThat(evidence.declaredSchema().fromColumns()).isEqualTo(List.of("CUSTOMER_ID"));
        assertThat(evidence.declaredSchema().toColumns()).isEqualTo(List.of("ID"));
    }

    @Test
    void declaredFkEdgeGetsObservedQueryLayerWhenMatchingPairsExist() {
        List<QueryUsage> usages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            usages.add(simple("obs" + i + ".sql",
                    "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        }
        usageCatalog.rebuild(usages);
        List<RelationshipEdge> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        RelationshipEvidence evidence = evidence(edges.get(0));
        assertThat(evidence.declaredSchema()).isNotNull();
        assertThat(evidence.observedQuery()).isNotNull();
        assertThat(evidence.observedQuery().joinSupport()).isEqualTo(3);
        assertThat(evidence.observedQuery().sourceRefs()).hasSize(3);
    }

    @Test
    void declaredFkEdgeGetsSemanticUsageLayerFromSharedBusinessTerms() {
        usageCatalog.rebuild(List.of(
                richQuery("InvoiceReport.json", "header",
                        "Invoice payer header", "Customers",
                        "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                        "payer_name", "Payer name", "Invoice payer"),
                richQuery("ShipmentReport.json", "main",
                        "Customer shipments", "Customers",
                        "SELECT o.id AS order_id FROM orders o JOIN customers c ON o.customer_id = c.id",
                        "order_id", "Order id", "Invoice payer")));
        List<RelationshipEdge> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), true);

        RelationshipEvidence evidence = evidence(edges.get(0));
        assertThat(evidence.declaredSchema()).isNotNull();
        assertThat(evidence.observedQuery()).isNotNull();
        SemanticEdgeEvidence semantic = evidence.semanticUsage();
        assertThat(semantic).isNotNull();
        assertThat(semantic.coOccurringQueryCount()).isEqualTo(2);
        assertThat(termValues(semantic, "sharedBusinessDomains")).contains("Customers");
        assertThat(termValues(semantic, "sharedBusinessObjects")).contains("Invoice payer");
        assertThat(termValues(semantic, "sharedOutputLabels")).contains("Payer name", "Order id");
    }

    @Test
    void appendsObservedOnlyEdgeWithObservedQueryLayer() {
        usageCatalog.rebuild(List.of(simple("q.sql",
                "SELECT * FROM events e JOIN sessions s ON e.session_id = s.id")));

        List<RelationshipEdge> edges = new ArrayList<>();
        probe.run(edges, Set.of("EVENTS", "SESSIONS"), true);

        assertThat(edges).singleElement()
                .satisfies(e -> {
                    assertThat(e.relationshipType()).isEqualTo("observed");
                    assertThat(e.undirected()).isEqualTo(true);
                    assertThat(e.fromTable()).isEqualTo("EVENTS");
                    assertThat(e.toTable()).isEqualTo("SESSIONS");
                    RelationshipEvidence evidence = evidence(e);
                    assertThat(evidence.declaredSchema()).isNull();
                    assertThat(evidence.observedQuery()).isNotNull();
                });
    }

    @Test
    void doesNotAppendObservedEdgeWhenOtherSideIsOutOfScope() {
        usageCatalog.rebuild(List.of(simple("q.sql",
                "SELECT * FROM events e JOIN sessions s ON e.session_id = s.id")));

        List<RelationshipEdge> edges = new ArrayList<>();
        probe.run(edges, Set.of("EVENTS"), true); // sessions is not in described scope

        assertThat(edges).isEmpty();
    }

    @Test
    void includeObservedFalseSkipsCatalogEntirelyButStillStampsDeclaredSchema() {
        List<QueryUsage> usages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            usages.add(simple("obs" + i + ".sql",
                    "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        }
        usageCatalog.rebuild(usages);
        List<RelationshipEdge> edges = new ArrayList<>();
        edges.add(edge("foreignKey", "ORDERS", "CUSTOMER_ID", "CUSTOMERS", "ID"));

        probe.run(edges, Set.of("ORDERS", "CUSTOMERS"), false);

        assertThat(edges).hasSize(1);
        RelationshipEvidence evidence = evidence(edges.get(0));
        assertThat(evidence.declaredSchema()).isNotNull();
        assertThat(evidence.observedQuery()).isNull();
        assertThat(evidence.semanticUsage()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------------------------------

    private static RelationshipEdge edge(String type, String fromTable, String fromColumn,
                                            String toTable, String toColumn) {
        return new RelationshipEdge(
                type,
                type + "_" + fromTable + "_" + fromColumn,
                "APP",
                fromTable,
                List.of(fromColumn),
                "APP",
                toTable,
                List.of(toColumn),
                null,
                null);
    }

    private static RelationshipEvidence evidence(RelationshipEdge edge) {
        RelationshipEvidence raw = edge.evidence();
        assertThat(raw).as("edge evidence bundle").isNotNull();
        return raw;
    }

    private static List<String> termValues(SemanticEdgeEvidence semantic, String key) {
        return (switch (key) {
            case "sharedBusinessDomains" -> semantic.sharedBusinessDomains();
            case "sharedBusinessObjects" -> semantic.sharedBusinessObjects();
            case "sharedOutputLabels" -> semantic.sharedOutputLabels();
            default -> List.<ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTermEvidence>of();
        }).stream()
                .map(term -> term.value())
                .toList();
    }

    private static QueryUsage simple(String path, String sql) {
        return new QueryUsage(
                new QueryUsageSource("dao", path, null),
                null, null, null, sql,
                null, null, null, null);
    }

    private static QueryUsage richQuery(String path, String unit,
                                        String label, String domain, String sql,
                                        String alias, String outputLabel, String businessObject) {
        return new QueryUsage(
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

        void run(List<RelationshipEdge> edges, Set<String> describedNamesUpper,
                 boolean includeObserved) {
            decorateAndAppendObserved(edges, describedNamesUpper, includeObserved);
        }
    }
}
