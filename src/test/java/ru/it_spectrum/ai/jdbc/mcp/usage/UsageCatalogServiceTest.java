package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTermEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.TableEvidenceProfile;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.CatalogQueryDetail;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByColumnResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByTableResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownDomainsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownTagsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ObservedRelationshipsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.RebuildResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ReresolveResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
import ru.it_spectrum.ai.jdbc.mcp.tools.JsonResponses;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageConfidence;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageFieldUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageLocation;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutput;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageOutputColumn;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageParameter;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageSource;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageTransformation;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageTransformationKind;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UsageCatalogServiceTest {

    private UsageCatalogService service;

    @BeforeEach
    void setUp() throws Exception {
        UsageProperties properties = new UsageProperties(true, List.of(), false, false, false, "");
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties);
        service = new UsageCatalogService(properties, ds, new QueryAnalysisService(),
                new JsonResponses(new JsonConfig().jdbcMcpObjectMapper()));
    }

    @Test
    void rebuildParsedQueryExtractsTablesColumnsAndJoinPairs() {
        String sql = """
                SELECT c.name, o.id
                FROM customers c
                LEFT JOIN orders o ON o.customer_id = c.id
                WHERE c.status = :status
                """;
        QueryUsage req = baseRequest("SHOP", "app/dao/CustomerOrders.java", "findOrders", sql);

        RebuildResult result = service.rebuild(List.of(req));

        assertThat(result.recordsLoaded()).isEqualTo(1);
        assertThat(result.parseFailed()).isEqualTo(0);
        assertThat(result.tablesExtracted()).isEqualTo(2);
        assertThat(result.columnsExtracted()).isGreaterThanOrEqualTo(4);
        assertThat(result.joinPairsExtracted()).isEqualTo(1);

        CatalogQueryDetail stored = service.getQuery("SHOP/app/dao/CustomerOrders.java#findOrders");
        assertThat(stored.tables())
                .extracting(CatalogQueryDetail.Table::tableResolved)
                .contains("CUSTOMERS", "ORDERS");
        assertThat(stored.joinPairs())
                .singleElement()
                .satisfies(pair -> {
                    assertThat(pair.left().table()).isEqualTo("ORDERS");
                    assertThat(pair.left().column()).isEqualTo("CUSTOMER_ID");
                    assertThat(pair.right().table()).isEqualTo("CUSTOMERS");
                    assertThat(pair.right().column()).isEqualTo("ID");
                });
    }

    @Test
    void rebuildRejectsUnsupportedSchemaVersion() {
        QueryUsage req = new QueryUsage(
                2,
                "SHOP",
                new QueryUsageSource("manual", "q.sql", null),
                null,
                null,
                null,
                "SELECT 1",
                null,
                null,
                null,
                null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.rebuild(List.of(req)))
                .withMessage("schemaVersion must be 1");
    }

    @Test
    void rebuildStoresParametersOutputsAndFieldUsages() {
        String sql = "SELECT c.name AS cust_name FROM customers c WHERE c.id = :customerId";
        QueryUsage req = new QueryUsage(
                "SHOP",
                new QueryUsageSource("dao", "CustomerDao.java", "findOne"),
                "Карточка клиента",
                "Customers",
                List.of("customer", "card"),
                sql,
                List.of(new QueryUsageParameter("customerId", "number", null, true,
                        "Идентификатор клиента", "PK таблицы CUSTOMERS")),
                List.of(new QueryUsageOutput("cust_name", "c.name", "Имя клиента", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage(
                        "cust_name",
                        "Карточка клиента / Шапка",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, "Прямое отображение"),
                        new QueryUsageLocation("ui-label", Map.of("widgetId", "lblName")),
                        List.of("Полное имя"),
                        QueryUsageConfidence.HIGH)),
                Map.of("origin", "manual"));

        service.rebuild(List.of(req));

        CatalogQueryDetail stored = service.getQuery("SHOP/CustomerDao.java#findOne");

        assertThat(stored.parameters()).singleElement()
                .satisfies(p -> {
                    assertThat(p.name()).isEqualTo("customerId");
                    assertThat(p.businessLabel()).isEqualTo("Идентификатор клиента");
                });
        assertThat(stored.outputs()).singleElement()
                .satisfies(o -> {
                    assertThat(o.alias()).isEqualTo("cust_name");
                    assertThat(o.businessLabel()).isEqualTo("Имя клиента");
                    assertThat(o.derivedFromColumns()).singleElement()
                            .satisfies(d -> {
                                assertThat(d.table()).isEqualTo("CUSTOMERS");
                                assertThat(d.column()).isEqualTo("NAME");
                            });
                });
        assertThat(stored.fieldUsages()).singleElement()
                .satisfies(fu -> {
                    assertThat(fu.transformation().kind()).isEqualTo("identity");
                    assertThat(fu.confidence()).isEqualTo("high");
                });
        assertThat(stored.tags()).contains("customer", "card");
    }

    @Test
    void tableEvidenceProfileAggregatesObservedAndSemanticUsage() {
        QueryUsage customerCard = new QueryUsage(
                "SHOP",
                new QueryUsageSource("dao", "CustomerDao.java", "findOne"),
                "Customer card query",
                "Customers",
                List.of("customer", "card"),
                "SELECT c.name AS customer_name FROM customers c WHERE c.status = :status",
                List.of(new QueryUsageParameter("status", "varchar", "ACTIVE", true,
                        "Customer status", null)),
                List.of(new QueryUsageOutput("customer_name", "c.name", "Customer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage(
                        "customer_name",
                        "Customer card",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        new QueryUsageLocation("ui-label", Map.of("screen", "customer-card")),
                        List.of("Customer"),
                        QueryUsageConfidence.HIGH)),
                null);
        QueryUsage invoice = new QueryUsage(
                "SHOP",
                new QueryUsageSource("report", "InvoiceReport.json", "header"),
                "Invoice header",
                "Billing",
                List.of("invoice"),
                "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("payer_name", "c.name", "Payer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage(
                        "payer_name",
                        "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        new QueryUsageLocation("report-field", Map.of("section", "header")),
                        List.of("Payer"),
                        QueryUsageConfidence.MEDIUM)),
                null);

        service.rebuild(List.of(customerCard, invoice));

        TableEvidenceProfile profile = service.tableEvidenceProfile(null, "customers");

        assertThat(profile.observedQuery().queryCount()).isEqualTo(2);
        assertThat(profile.observedQuery().columns())
                .anySatisfy(column -> {
                    assertThat(column.column()).isEqualTo("NAME");
                    assertThat(column.queryCount()).isEqualTo(2);
                })
                .anySatisfy(column -> {
                    assertThat(column.column()).isEqualTo("STATUS");
                    assertThat(column.contexts()).extracting(SemanticTermEvidence::value).contains("where");
                });

        SemanticTableUsage semantic = profile.semanticUsage();
        assertThat(asEvidenceValues(semantic, "businessDomains")).contains("Customers", "Billing");
        assertThat(asEvidenceValues(semantic, "businessTags")).contains("customer", "invoice");
        assertThat(asEvidenceValues(semantic, "outputLabels")).contains("Customer name", "Payer name");
        assertThat(asEvidenceValues(semantic, "businessObjects")).contains("Customer card", "Invoice payer");
    }

    @Test
    void semanticTableCandidatesFindTablesByBusinessTerms() {
        QueryUsage payerReport = new QueryUsage(
                "SHOP",
                new QueryUsageSource("report", "InvoiceReport.json", "header"),
                "Invoice payer header",
                "Billing",
                List.of("invoice", "payer"),
                "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("payer_name", "c.name", "Payer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage(
                        "payer_name",
                        "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null,
                        null,
                        QueryUsageConfidence.HIGH)),
                null);
        QueryUsage inventoryReport = new QueryUsage(
                "SHOP",
                new QueryUsageSource("report", "InventoryReport.json", "main"),
                "Warehouse stock",
                "Inventory",
                List.of("stock"),
                "SELECT id FROM products",
                null,
                null,
                null,
                null);

        service.rebuild(List.of(payerReport, inventoryReport));

        List<SemanticTableCandidate> candidates = service.semanticTableCandidates(null, "payer", 10);

        assertThat(candidates)
                .extracting(SemanticTableCandidate::table)
                .contains("CUSTOMERS");
        SemanticTableCandidate customers = candidates.stream()
                .filter(c -> "CUSTOMERS".equals(c.table()))
                .findFirst()
                .orElseThrow();
        assertThat(customers.support()).isGreaterThanOrEqualTo(3);
        assertThat(customers.queryUids()).contains("SHOP/InvoiceReport.json#header");
        assertThat(customers.matchedTerms())
                .extracting(term -> term.value())
                .contains("business_tag:payer", "query_label:Invoice payer header",
                        "output_label:Payer name", "business_object:Invoice payer");
    }

    @Test
    void rebuildReplacesPreviousRows() {
        String sqlV1 = "SELECT id FROM customers WHERE id = :id";
        String sqlV2 = "SELECT id, name, status FROM customers WHERE id = :id";
        QueryUsageSource source = new QueryUsageSource("dao", "CustomerDao.java", "findOne");

        service.rebuild(List.of(buildRequest("SHOP", source, sqlV1)));
        service.rebuild(List.of(buildRequest("SHOP", source, sqlV2)));

        CatalogQueryDetail stored = service.getQuery("SHOP/CustomerDao.java#findOne");
        assertThat(stored.rawSql()).isEqualTo(sqlV2);
        assertThat(stored.columns()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(stored.columns())
                .extracting(CatalogQueryDetail.Column::columnName)
                .contains("ID", "NAME", "STATUS");
    }

    @Test
    void parseFailureKeepsBusinessMetadata() {
        String unparsable = "SELECT FROM";
        QueryUsage req = new QueryUsage(
                "SHOP",
                new QueryUsageSource("manual", "broken.sql", null),
                "Сломанный запрос",
                null, null,
                unparsable,
                List.of(new QueryUsageParameter("p", null, null, null, "Параметр", null)),
                List.of(new QueryUsageOutput("a", null, "Колонка А", null, null)),
                List.of(new QueryUsageFieldUsage(
                        "a", "Где-то",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null, null, null)),
                null);

        RebuildResult result = service.rebuild(List.of(req));

        assertThat(result.parseFailed()).isEqualTo(1);
        CatalogQueryDetail stored = service.getQuery("SHOP/broken.sql");
        assertThat(stored.parseStatus()).isEqualTo("failed");
        assertThat(stored.parseError()).isNotNull();
        assertThat(stored.parameters()).hasSize(1);
        assertThat(stored.outputs()).hasSize(1);
        assertThat(stored.fieldUsages()).hasSize(1);
    }

    @Test
    void findQueriesByTableIsCaseInsensitive() {
        service.rebuild(List.of(
                buildSimple("SHOP", "a.sql", "SELECT * FROM Customers WHERE id = 1"),
                buildSimple("SHOP", "b.sql", "SELECT * FROM ORDERS WHERE id = 1")));

        FindQueriesByTableResult byLower = service.findQueriesByTable(null, "customers");
        assertThat(byLower.count()).isEqualTo(1);
        assertThat(byLower.matches())
                .extracting(FindQueriesByTableResult.Match::sourcePath).contains("a.sql");

        FindQueriesByTableResult byUpper = service.findQueriesByTable(null, "ORDERS");
        assertThat(byUpper.count()).isEqualTo(1);
    }

    @Test
    void findQueriesByColumnReportsContext() {
        service.rebuild(List.of(buildSimple("SHOP", "a.sql",
                "SELECT name FROM customers WHERE status = 'A' ORDER BY name")));

        FindQueriesByColumnResult nameMatches = service.findQueriesByColumn(null, "customers", "name");
        assertThat(nameMatches.matches())
                .extracting(FindQueriesByColumnResult.Match::context)
                .contains("select", "order_by");

        FindQueriesByColumnResult statusMatches = service.findQueriesByColumn(null, null, "status");
        assertThat(statusMatches.matches())
                .extracting(FindQueriesByColumnResult.Match::context)
                .contains("where");
    }

    @Test
    void observedRelationshipsAggregatesAcrossQueries() {
        service.rebuild(List.of(
                buildSimple("SHOP", "q1.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"),
                buildSimple("SHOP", "q2.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.id = 1"),
                buildSimple("SHOP", "q3.sql",
                        "SELECT * FROM payments p JOIN customers c ON p.customer_id = c.id")));

        ObservedRelationshipsResult all = service.observedRelationships(null, null, 1);
        assertThat(all.relationships()).hasSize(2);

        ObservedRelationshipsResult popular = service.observedRelationships(null, null, 2);
        assertThat(popular.relationships()).hasSize(1);
        assertThat(popular.relationships().get(0).support()).isEqualTo(2);
        assertThat(popular.relationships().get(0).left().table()).isEqualTo("ORDERS");
    }

    @Test
    void listKnownTagsAndDomainsAggregateCounts() {
        QueryUsage req1 = new QueryUsage(
                "SHOP", new QueryUsageSource("dao", "a.sql", null),
                null, "Customers", List.of("customer"),
                "SELECT 1 FROM dual", null, null, null, null);
        QueryUsage req2 = new QueryUsage(
                "SHOP", new QueryUsageSource("dao", "b.sql", null),
                null, "Customers", List.of("customer", "vip"),
                "SELECT 1 FROM dual", null, null, null, null);
        service.rebuild(List.of(req1, req2));

        KnownTagsResult tags = service.listKnownTags("SHOP");
        assertThat(tags.tags())
                .extracting(KnownTagsResult.TagEntry::tag)
                .containsExactlyInAnyOrder("customer", "vip");

        KnownDomainsResult domains = service.listKnownDomains("SHOP");
        assertThat(domains.domains())
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.domain()).isEqualTo("Customers");
                    assertThat(d.count()).isEqualTo(2);
                });
    }

    @Test
    void rejectsMissingTransformationOnFieldUsage() {
        QueryUsage req = new QueryUsage(
                "SHOP", new QueryUsageSource("manual", "x.sql", null),
                null, null, null,
                "SELECT 1 FROM dual",
                null, null,
                List.of(new QueryUsageFieldUsage(
                        null, "obj", null, null, null, null)),
                null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.rebuild(List.of(req)))
                .withMessageContaining("transformation.kind");
    }

    @Test
    void observedEdgesReturnsTypedAggregates() {
        service.rebuild(List.of(
                buildSimple("SHOP", "q1.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"),
                buildSimple("SHOP", "q2.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"),
                buildSimple("SHOP", "q3.sql",
                        "SELECT * FROM payments p JOIN customers c ON p.customer_id = c.id")));

        java.util.List<UsageCatalogService.ObservedEdge> all = service.observedEdges(null, 1);
        assertThat(all).hasSize(2);

        java.util.List<UsageCatalogService.ObservedEdge> ordersOnly =
                service.observedEdges(java.util.Set.of("ORDERS"), 1);
        assertThat(ordersOnly).hasSize(1);
        UsageCatalogService.ObservedEdge edge = ordersOnly.get(0);
        assertThat(edge.support()).isEqualTo(2);
        assertThat(edge.queryUids()).hasSize(2);
        assertThat(edge.leftTable()).isEqualTo("ORDERS");
        assertThat(edge.rightTable()).isEqualTo("CUSTOMERS");
    }

    @Test
    void reresolveFillsSchemaWhenLookupReturnsExactlyOneMatch() {
        service.rebuild(List.of(
                buildSimple("SHOP", "q1.sql",
                        "SELECT name FROM customers WHERE id = :id"),
                buildSimple("SHOP", "q2.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id")));

        ReresolveResult result = service.reresolve("SHOP", name -> {
            List<String[]> matches = new java.util.ArrayList<>();
            if ("CUSTOMERS".equals(name) || "ORDERS".equals(name)) {
                matches.add(new String[]{"app", name});
            }
            return matches;
        });

        assertThat(result.tablesResolved()).isEqualTo(2);
        assertThat(result.tablesAmbiguous()).isEqualTo(0);
        assertThat(result.tablesUnresolved()).isEqualTo(0);

        CatalogQueryDetail stored = service.getQuery("SHOP/q2.sql");
        assertThat(stored.tables())
                .allSatisfy(t -> assertThat(t.schemaResolved()).isEqualTo("APP"));
        assertThat(stored.joinPairs()).singleElement()
                .satisfies(jp -> {
                    assertThat(jp.left().schema()).isEqualTo("APP");
                    assertThat(jp.right().schema()).isEqualTo("APP");
                });
        assertThat(stored.columns())
                .allSatisfy(c -> assertThat(c.schemaResolved()).isEqualTo("APP"));
    }

    @Test
    void reresolveMarksAmbiguousWhenMultipleMatchesFound() {
        service.rebuild(List.of(buildSimple("SHOP", "q.sql", "SELECT id FROM orders")));

        ReresolveResult result = service.reresolve("SHOP", name -> {
            List<String[]> matches = new java.util.ArrayList<>();
            matches.add(new String[]{"app", name});
            matches.add(new String[]{"audit", name});
            return matches;
        });

        assertThat(result.tablesAmbiguous()).isEqualTo(1);
        CatalogQueryDetail stored = service.getQuery("SHOP/q.sql");
        assertThat(stored.tables()).singleElement()
                .satisfies(t -> {
                    assertThat(t.resolutionStatus()).isEqualTo("ambiguous");
                    assertThat(t.schemaResolved()).isNull();
                });
    }

    @Test
    void reresolveLeavesUnresolvedWhenLookupReturnsNothing() {
        service.rebuild(List.of(buildSimple("SHOP", "q.sql", "SELECT id FROM orders")));

        ReresolveResult result = service.reresolve("SHOP", name -> new java.util.ArrayList<>());

        assertThat(result.tablesUnresolved()).isEqualTo(1);
        CatalogQueryDetail stored = service.getQuery("SHOP/q.sql");
        assertThat(stored.tables()).singleElement()
                .satisfies(t -> assertThat(t.resolutionStatus()).isEqualTo("unresolved"));
    }

    @Test
    void semanticEdgeEvidenceReturnsSharedTermsAcrossCoOccurringQueries() {
        QueryUsage payerReport = new QueryUsage(
                "SHOP",
                new QueryUsageSource("report", "InvoiceReport.json", "header"),
                "Invoice payer header",
                "Customers",
                List.of("invoice", "payer"),
                "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("payer_name", "c.name", "Payer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage(
                        "payer_name",
                        "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null,
                        null,
                        QueryUsageConfidence.HIGH)),
                null);
        QueryUsage shipmentReport = new QueryUsage(
                "SHOP",
                new QueryUsageSource("report", "ShipmentReport.json", "main"),
                "Customer shipments",
                "Customers",
                List.of("shipment"),
                "SELECT o.id AS order_id FROM orders o JOIN customers c ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("order_id", "o.id", "Order id", null,
                        List.of(new QueryUsageOutputColumn(null, "orders", "id")))),
                List.of(new QueryUsageFieldUsage(
                        "order_id",
                        "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null,
                        null,
                        QueryUsageConfidence.HIGH)),
                null);
        QueryUsage standalone = new QueryUsage(
                "SHOP",
                new QueryUsageSource("report", "InventoryReport.json", "main"),
                "Stock",
                "Inventory",
                List.of("stock"),
                "SELECT id FROM products",
                null, null, null, null);

        service.rebuild(List.of(payerReport, shipmentReport, standalone));

        SemanticEdgeEvidence evidence = service.semanticEdgeEvidence(null, "orders", null, "customers");

        assertThat(evidence.coOccurringQueryCount()).isEqualTo(2);
        assertThat(evidence.coOccurringQueryUids())
                .contains("SHOP/InvoiceReport.json#header", "SHOP/ShipmentReport.json#main");
        assertThat(evidence.sharedBusinessDomains())
                .extracting(SemanticTermEvidence::value)
                .containsOnly("Customers");
        assertThat(evidence.sharedBusinessObjects())
                .extracting(SemanticTermEvidence::value)
                .contains("Invoice payer");
        assertThat(evidence.sharedOutputLabels())
                .extracting(SemanticTermEvidence::value)
                .contains("Payer name", "Order id");
    }

    @Test
    void semanticEdgeEvidenceReturnsEmptyWhenNoQueriesTouchBothTables() {
        service.rebuild(List.of(
                buildSimple("SHOP", "a.sql", "SELECT id FROM customers"),
                buildSimple("SHOP", "b.sql", "SELECT id FROM products")));

        SemanticEdgeEvidence evidence = service.semanticEdgeEvidence(null, "customers", null, "products");

        assertThat(evidence.coOccurringQueryCount()).isZero();
        assertThat(evidence.isEmpty()).isTrue();
    }

    @Test
    void getQueryThrowsForUnknownUid() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getQuery("SHOP/missing.sql"))
                .withMessageContaining("not found");
    }

    // ---------------------------------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------------------------------

    private static QueryUsage baseRequest(String dataSource, String path, String unit, String sql) {
        return new QueryUsage(
                dataSource,
                new QueryUsageSource("dao", path, unit),
                null, null, null,
                sql,
                null, null, null, null);
    }

    private static QueryUsage buildRequest(String dataSource, QueryUsageSource source, String sql) {
        return new QueryUsage(
                dataSource, source, null, null, null, sql,
                null, null, null, null);
    }

    private static QueryUsage buildSimple(String dataSource, String path, String sql) {
        return baseRequest(dataSource, path, null, sql);
    }

    private static List<String> asEvidenceValues(SemanticTableUsage root, String key) {
        return (switch (key) {
            case "businessDomains" -> root.businessDomains();
            case "businessTags" -> root.businessTags();
            case "outputLabels" -> root.outputLabels();
            case "businessObjects" -> root.businessObjects();
            default -> List.<SemanticTermEvidence>of();
        }).stream()
                .map(SemanticTermEvidence::value)
                .toList();
    }
}