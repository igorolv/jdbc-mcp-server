package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticEdgeEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableUsage;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTableCandidate;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.SemanticTermEvidence;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.TableEvidenceProfile;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QueryDetail;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByColumnResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.FindQueriesByTableResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownDomainsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.KnownTagsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ListQueriesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.ObservedRelationshipsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.QuerySourceRef;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.InvalidateUsageCatalogCacheResult;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UsageCatalogServiceTest {

    private UsageCatalogService service;

    @BeforeEach
    void setUp() throws Exception {
        UsageProperties properties = new UsageProperties(true, List.of(), List.of(), true, true, true, 1_000);
        JdbcMcpProperties jdbcMcpProperties = new JdbcMcpProperties("");
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties, jdbcMcpProperties);
        service = new UsageCatalogService(properties, ds, new QueryAnalysisService(),
                new JsonResponses(new JsonConfig().jdbcMcpObjectMapper()), null);
    }

    @Test
    void rebuildParsedQueryExtractsTablesColumnsAndJoinPairs() {
        String sql = """
                SELECT c.name, o.id
                FROM customers c
                LEFT JOIN orders o ON o.customer_id = c.id
                WHERE c.status = :status
                """;
        QueryUsage req = baseRequest("app/dao/CustomerOrders.java", "findOrders", sql);

        InvalidateUsageCatalogCacheResult result = service.rebuild(List.of(req));

        assertThat(result.recordsLoaded()).isEqualTo(1);

        QueryDetail stored = service.getQuery("dao", "app/dao/CustomerOrders.java", "findOrders");
        assertThat(stored.tables())
                .extracting(QueryDetail.Table::tableResolved)
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

        QueryDetail stored = service.getQuery("dao", "CustomerDao.java", "findOne");

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
        QueryUsage customerCard = ref("dao", "CustomerDao.java", "findOne", "Customer card query",
                "Customers", List.of("customer", "card"),
                "SELECT c.name AS customer_name FROM customers c WHERE c.status = :status",
                List.of(new QueryUsageParameter("status", "varchar", "ACTIVE", true,
                        "Customer status", null)),
                List.of(new QueryUsageOutput("customer_name", "c.name", "Customer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage("customer_name", "Customer card",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        new QueryUsageLocation("ui-label", Map.of("screen", "customer-card")),
                        List.of("Customer"), QueryUsageConfidence.HIGH)));
        QueryUsage invoice = ref("report", "InvoiceReport.json", "header", "Invoice header",
                "Billing", List.of("invoice"),
                "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("payer_name", "c.name", "Payer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage("payer_name", "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        new QueryUsageLocation("report-field", Map.of("section", "header")),
                        List.of("Payer"), QueryUsageConfidence.MEDIUM)));

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
        QueryUsage payerReport = ref("report", "InvoiceReport.json", "header",
                "Invoice payer header", "Billing", List.of("invoice", "payer"),
                "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("payer_name", "c.name", "Payer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage("payer_name", "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null, null, QueryUsageConfidence.HIGH)));
        QueryUsage inventoryReport = ref("report", "InventoryReport.json", "main",
                "Warehouse stock", "Inventory", List.of("stock"),
                "SELECT id FROM products",
                null, null, null);

        service.rebuild(List.of(payerReport, inventoryReport));

        List<SemanticTableCandidate> candidates = service.semanticTableCandidates(null, "payer", 10);

        assertThat(candidates)
                .extracting(SemanticTableCandidate::table)
                .contains("CUSTOMERS");
        SemanticTableCandidate cust = candidates.stream()
                .filter(c -> "CUSTOMERS".equals(c.table()))
                .findFirst()
                .orElseThrow();
        assertThat(cust.support()).isGreaterThanOrEqualTo(3);
        assertThat(cust.sourceRefs())
                .extracting(QuerySourceRef::sourcePath)
                .contains("InvoiceReport.json");
        assertThat(cust.matchedTerms())
                .extracting(SemanticTermEvidence::value)
                .contains("business_tag:payer", "query_label:Invoice payer header",
                        "output_label:Payer name", "business_object:Invoice payer");
    }

    @Test
    void rebuildReplacesPreviousRows() {
        String sqlV1 = "SELECT id FROM customers WHERE id = :id";
        String sqlV2 = "SELECT id, name, status FROM customers WHERE id = :id";
        QueryUsageSource src = new QueryUsageSource("dao", "CustomerDao.java", "findOne");

        service.rebuild(List.of(buildRequest(src, sqlV1)));
        service.rebuild(List.of(buildRequest(src, sqlV2)));

        QueryDetail stored = service.getQuery("dao", "CustomerDao.java", "findOne");
        assertThat(stored.rawSql()).isEqualTo(sqlV2);
        assertThat(stored.columns()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(stored.columns())
                .extracting(QueryDetail.Column::columnName)
                .contains("ID", "NAME", "STATUS");
    }

    @Test
    void parseFailureKeepsBusinessMetadata() {
        String unparsable = "SELECT FROM";
        QueryUsage req = ref("manual", "broken.sql", null,
                "Сломанный запрос", null, null,
                unparsable,
                List.of(new QueryUsageParameter("p", null, null, null, "Параметр", null)),
                List.of(new QueryUsageOutput("a", null, "Колонка А", null, null)),
                List.of(new QueryUsageFieldUsage("a", "Где-то",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null, null, null)));

        InvalidateUsageCatalogCacheResult result = service.rebuild(List.of(req));

        assertThat(result.recordsLoaded()).isEqualTo(1);
        QueryDetail stored = service.getQuery("manual", "broken.sql", null);
        assertThat(stored.parseStatus()).isEqualTo("failed");
        assertThat(stored.parameters()).hasSize(1);
        assertThat(stored.outputs()).hasSize(1);
        assertThat(stored.fieldUsages()).hasSize(1);
    }

    @Test
    void findQueriesByTableIsCaseInsensitive() {
        service.rebuild(List.of(
                buildSimple("a.sql", "SELECT * FROM Customers WHERE id = 1"),
                buildSimple("b.sql", "SELECT * FROM ORDERS WHERE id = 1")));

        FindQueriesByTableResult byLower = service.findQueriesByTable(null, "customers");
        assertThat(byLower.count()).isEqualTo(1);
        assertThat(byLower.matches())
                .extracting(FindQueriesByTableResult.Match::sourcePath).contains("a.sql");

        FindQueriesByTableResult byUpper = service.findQueriesByTable(null, "ORDERS");
        assertThat(byUpper.count()).isEqualTo(1);
    }

    @Test
    void findQueriesByColumnReportsContext() {
        service.rebuild(List.of(buildSimple("a.sql",
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
                buildSimple("q1.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"),
                buildSimple("q2.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.id = 1"),
                buildSimple("q3.sql",
                        "SELECT * FROM payments p JOIN customers c ON p.customer_id = c.id")));

        ObservedRelationshipsResult all = service.observedRelationships(null, null, 1);
        assertThat(all.relationships()).hasSize(2);

        ObservedRelationshipsResult popular = service.observedRelationships(null, null, 2);
        assertThat(popular.relationships()).hasSize(1);
        assertThat(popular.relationships().getFirst().support()).isEqualTo(2);
        assertThat(popular.relationships().getFirst().left().table()).isEqualTo("ORDERS");
    }

    @Test
    void listKnownTagsAndDomainsAggregateCounts() {
        QueryUsage req1 = ref("dao", "a.sql", null, null, "Customers", List.of("customer"),
                "SELECT 1 FROM dual", null, null, null);
        QueryUsage req2 = ref("dao", "b.sql", null, null, "Customers", List.of("customer", "vip"),
                "SELECT 1 FROM dual", null, null, null);
        service.rebuild(List.of(req1, req2));

        KnownTagsResult tags = service.listKnownTags();
        assertThat(tags.tags())
                .extracting(KnownTagsResult.TagEntry::tag)
                .containsExactlyInAnyOrder("customer", "vip");

        KnownDomainsResult domains = service.listKnownDomains();
        assertThat(domains.domains())
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.domain()).isEqualTo("Customers");
                    assertThat(d.count()).isEqualTo(2);
                });
    }

    @Test
    void rejectsMissingTransformationOnFieldUsage() {
        QueryUsage req = ref("manual", "x.sql", null, null, null, null,
                "SELECT 1 FROM dual",
                null, null,
                List.of(new QueryUsageFieldUsage(null, "obj", null, null, null, null)));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.rebuild(List.of(req)))
                .withMessageContaining("transformation.kind");
    }

    @Test
    void observedEdgesReturnsTypedAggregates() {
        service.rebuild(List.of(
                buildSimple("q1.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"),
                buildSimple("q2.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"),
                buildSimple("q3.sql",
                        "SELECT * FROM payments p JOIN customers c ON p.customer_id = c.id")));

        java.util.List<UsageCatalogService.ObservedEdge> all = service.observedEdges(null, 1);
        assertThat(all).hasSize(2);

        java.util.List<UsageCatalogService.ObservedEdge> ordersOnly =
                service.observedEdges(java.util.Set.of("ORDERS"), 1);
        assertThat(ordersOnly).hasSize(1);
        UsageCatalogService.ObservedEdge edge = ordersOnly.getFirst();
        assertThat(edge.support()).isEqualTo(2);
        assertThat(edge.sourceRefs()).hasSize(2);
        assertThat(edge.leftTable()).isEqualTo("ORDERS");
        assertThat(edge.rightTable()).isEqualTo("CUSTOMERS");
    }

    @Test
    void reresolveFillsSchemaWhenLookupReturnsExactlyOneMatch() {
        service.rebuild(List.of(
                buildSimple("q1.sql",
                        "SELECT name FROM customers WHERE id = :id"),
                buildSimple("q2.sql",
                        "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id")));

        service.reresolve(name -> {
            List<String[]> matches = new java.util.ArrayList<>();
            if ("CUSTOMERS".equals(name) || "ORDERS".equals(name)) {
                matches.add(new String[]{"app", name});
            }
            return matches;
        });

        QueryDetail stored = service.getQuery("dao", "q2.sql", null);
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
        service.rebuild(List.of(buildSimple("q.sql", "SELECT id FROM orders")));

        service.reresolve(name -> {
            List<String[]> matches = new java.util.ArrayList<>();
            matches.add(new String[]{"app", name});
            matches.add(new String[]{"audit", name});
            return matches;
        });

        QueryDetail stored = service.getQuery("dao", "q.sql", null);
        assertThat(stored.tables()).singleElement()
                .satisfies(t -> {
                    assertThat(t.resolutionStatus()).isEqualTo("ambiguous");
                    assertThat(t.schemaResolved()).isNull();
                });
    }

    @Test
    void reresolveLeavesUnresolvedWhenLookupReturnsNothing() {
        service.rebuild(List.of(buildSimple("q.sql", "SELECT id FROM orders")));

        service.reresolve(name -> new java.util.ArrayList<>());

        QueryDetail stored = service.getQuery("dao", "q.sql", null);
        assertThat(stored.tables()).singleElement()
                .satisfies(t -> assertThat(t.resolutionStatus()).isEqualTo("unresolved"));
    }

    @Test
    void semanticEdgeEvidenceReturnsSharedTermsAcrossCoOccurringQueries() {
        QueryUsage payerReport = ref("report", "InvoiceReport.json", "header",
                "Invoice payer header", "Customers", List.of("invoice", "payer"),
                "SELECT c.name AS payer_name FROM customers c JOIN orders o ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("payer_name", "c.name", "Payer name", null,
                        List.of(new QueryUsageOutputColumn(null, "customers", "name")))),
                List.of(new QueryUsageFieldUsage("payer_name", "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null, null, QueryUsageConfidence.HIGH)));
        QueryUsage shipmentReport = ref("report", "ShipmentReport.json", "main",
                "Customer shipments", "Customers", List.of("shipment"),
                "SELECT o.id AS order_id FROM orders o JOIN customers c ON o.customer_id = c.id",
                null,
                List.of(new QueryUsageOutput("order_id", "o.id", "Order id", null,
                        List.of(new QueryUsageOutputColumn(null, "orders", "id")))),
                List.of(new QueryUsageFieldUsage("order_id", "Invoice payer",
                        new QueryUsageTransformation(QueryUsageTransformationKind.IDENTITY, null),
                        null, null, QueryUsageConfidence.HIGH)));
        QueryUsage standalone = ref("report", "InventoryReport.json", "main",
                "Stock", "Inventory", List.of("stock"),
                "SELECT id FROM products",
                null, null, null);

        service.rebuild(List.of(payerReport, shipmentReport, standalone));

        SemanticEdgeEvidence evidence = service.semanticEdgeEvidence(null, "orders", null, "customers");

        assertThat(evidence.coOccurringQueryCount()).isEqualTo(2);
        assertThat(evidence.coOccurringSourceRefs())
                .extracting(QuerySourceRef::sourcePath)
                .contains("InvoiceReport.json", "ShipmentReport.json");
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
                buildSimple("a.sql", "SELECT id FROM customers"),
                buildSimple("b.sql", "SELECT id FROM products")));

        SemanticEdgeEvidence evidence = service.semanticEdgeEvidence(null, "customers", null, "products");

        assertThat(evidence.coOccurringQueryCount()).isZero();
        assertThat(evidence.isEmpty()).isTrue();
    }

    @Test
    void firstUsageAccessBuildsNativeSourceSynchronously() throws Exception {
        UsageProperties properties = lazyProperties();
        LazyNativeProvider provider = new LazyNativeProvider(
                properties,
                List.of(baseRef("dao", "native/package/APP.CUSTOMER_PKG",
                        "good.stmt1", "SELECT id FROM customers")));
        UsageCatalogService svc = newService(properties, provider);

        assertThat(svc.listQueries(null, null, null, null, null, null, 100, 0)
                .queries())
                .extracting(ListQueriesResult.QueryEntry::sourcePath)
                .containsExactly("native/package/APP.CUSTOMER_PKG");
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void invalidateMakesNextUsageAccessRebuildSources() throws Exception {
        UsageProperties properties = lazyProperties();
        LazyNativeProvider provider = new LazyNativeProvider(
                properties,
                List.of(baseRef("dao", "native/view/APP.CUSTOMERS_V",
                        null, "SELECT id FROM customers")),
                List.of(baseRef("dao", "native/view/APP.ORDERS_V",
                        null, "SELECT id FROM orders")));
        UsageCatalogService svc = newService(properties, provider);

        assertThat(svc.listQueries(null, null, null, null, null, null, 100, 0)
                .queries())
                .extracting(ListQueriesResult.QueryEntry::sourcePath)
                .contains("native/view/APP.CUSTOMERS_V");

        svc.invalidateIndex();

        assertThat(svc.listQueries(null, null, null, null, null, null, 100, 0)
                .queries())
                .extracting(ListQueriesResult.QueryEntry::sourcePath)
                .contains("native/view/APP.ORDERS_V")
                .doesNotContain("native/view/APP.CUSTOMERS_V");
        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    void getQueryThrowsForUnknownSource() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getQuery("dao", "missing.sql", null))
                .withMessageContaining("not found");
    }

    // ---------------------------------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------------------------------

    private static QueryUsage baseRequest(String path, String unit, String sql) {
        return new QueryUsage(
                new QueryUsageSource("dao", path, unit),
                null, null, null,
                sql,
                null, null, null, null);
    }

    private static QueryUsage baseRef(String kind, String path, String unit, String sql) {
        return new QueryUsage(
                new QueryUsageSource(kind, path, unit),
                null, null, null,
                sql,
                null, null, null, null);
    }

    private static QueryUsage buildRequest(QueryUsageSource source, String sql) {
        return new QueryUsage(source, null, null, null, sql, null, null, null, null);
    }

    private static QueryUsage buildSimple(String path, String sql) {
        return baseRequest(path, null, sql);
    }

    private static QueryUsage ref(String kind, String path, String unit,
                                  String label, String domain, List<String> tags,
                                  String sql,
                                  List<QueryUsageParameter> params,
                                  List<QueryUsageOutput> outputs,
                                  List<QueryUsageFieldUsage> fieldUsages) {
        return new QueryUsage(
                new QueryUsageSource(kind, path, unit),
                label, domain, tags,
                sql, params, outputs, fieldUsages, null);
    }

    private static UsageCatalogService newService(UsageProperties properties,
                                                  DatabaseNativeUsageSourceProvider nativeProvider)
            throws Exception {
        JdbcMcpProperties jdbcMcpProperties = new JdbcMcpProperties("");
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties, jdbcMcpProperties);
        return new UsageCatalogService(properties, ds, new QueryAnalysisService(),
                new JsonResponses(new JsonConfig().jdbcMcpObjectMapper()), nativeProvider);
    }

    private static UsageProperties lazyProperties() {
        return new UsageProperties(true, List.of(), List.of(), true, true, true, 100);
    }

    private static final class LazyNativeProvider extends DatabaseNativeUsageSourceProvider {
        private final List<List<QueryUsage>> batches;
        private int calls;

        @SafeVarargs
        private LazyNativeProvider(UsageProperties properties, List<QueryUsage>... batches) {
            super(properties, null, new ProceduralSqlExtractor());
            this.batches = Arrays.stream(batches).toList();
        }

        @Override
        public List<QueryUsage> load() {
            calls++;
            if (batches.isEmpty()) return List.of();
            return batches.get(Math.min(calls - 1, batches.size() - 1));
        }

        private int calls() {
            return calls;
        }
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