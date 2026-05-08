package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.model.evidence.TableEvidenceProfile;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;
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
        service = new UsageCatalogService(properties, ds, new QueryAnalysisService());
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

        Map<String, Object> result = service.rebuild(List.of(req));

        assertThat(result.get("recordsLoaded")).isEqualTo(1);
        assertThat(result.get("parseFailed")).isEqualTo(0);
        assertThat(result.get("tablesExtracted")).isEqualTo(2);
        assertThat((Integer) result.get("columnsExtracted")).isGreaterThanOrEqualTo(4);
        assertThat(result.get("joinPairsExtracted")).isEqualTo(1);

        Map<String, Object> stored = service.getQuery("SHOP/app/dao/CustomerOrders.java#findOrders");
        assertThat(asList(stored, "tables"))
                .extracting(row -> row.get("tableResolved"))
                .contains("CUSTOMERS", "ORDERS");
        assertThat(asList(stored, "joinPairs"))
                .singleElement()
                .satisfies(pair -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> left = (Map<String, Object>) pair.get("left");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> right = (Map<String, Object>) pair.get("right");
                    assertThat(left.get("table")).isEqualTo("ORDERS");
                    assertThat(left.get("column")).isEqualTo("CUSTOMER_ID");
                    assertThat(right.get("table")).isEqualTo("CUSTOMERS");
                    assertThat(right.get("column")).isEqualTo("ID");
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

        Map<String, Object> stored = service.getQuery("SHOP/CustomerDao.java#findOne");

        assertThat(asList(stored, "parameters")).singleElement()
                .satisfies(p -> {
                    assertThat(p.get("name")).isEqualTo("customerId");
                    assertThat(p.get("businessLabel")).isEqualTo("Идентификатор клиента");
                });
        assertThat(asList(stored, "outputs")).singleElement()
                .satisfies(o -> {
                    assertThat(o.get("alias")).isEqualTo("cust_name");
                    assertThat(o.get("businessLabel")).isEqualTo("Имя клиента");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> derived = (List<Map<String, Object>>) o.get("derivedFromColumns");
                    assertThat(derived).singleElement()
                            .satisfies(d -> {
                                assertThat(d.get("table")).isEqualTo("CUSTOMERS");
                                assertThat(d.get("column")).isEqualTo("NAME");
                            });
                });
        assertThat(asList(stored, "fieldUsages")).singleElement()
                .satisfies(fu -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tx = (Map<String, Object>) fu.get("transformation");
                    assertThat(tx.get("kind")).isEqualTo("identity");
                    assertThat(fu.get("confidence")).isEqualTo("high");
                });
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) stored.get("tags");
        assertThat(tags).contains("customer", "card");
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
        Map<String, Object> mapped = profile.toMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> observed = (Map<String, Object>) mapped.get("observedQuery");
        assertThat(observed.get("queryCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observedColumns = (List<Map<String, Object>>) observed.get("columns");
        assertThat(observedColumns)
                .anySatisfy(column -> {
                    assertThat(column.get("column")).isEqualTo("NAME");
                    assertThat(column.get("queryCount")).isEqualTo(2);
                })
                .anySatisfy(column -> {
                    assertThat(column.get("column")).isEqualTo("STATUS");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> contexts =
                            (List<Map<String, Object>>) column.get("contexts");
                    assertThat(contexts).extracting(c -> c.get("value")).contains("where");
                });

        @SuppressWarnings("unchecked")
        Map<String, Object> semantic = (Map<String, Object>) mapped.get("semanticUsage");
        assertThat(asEvidenceValues(semantic, "businessDomains")).contains("Customers", "Billing");
        assertThat(asEvidenceValues(semantic, "businessTags")).contains("customer", "invoice");
        assertThat(asEvidenceValues(semantic, "outputLabels")).contains("Customer name", "Payer name");
        assertThat(asEvidenceValues(semantic, "businessObjects")).contains("Customer card", "Invoice payer");
    }

    @Test
    void rebuildReplacesPreviousRows() {
        String sqlV1 = "SELECT id FROM customers WHERE id = :id";
        String sqlV2 = "SELECT id, name, status FROM customers WHERE id = :id";
        QueryUsageSource source = new QueryUsageSource("dao", "CustomerDao.java", "findOne");

        service.rebuild(List.of(buildRequest("SHOP", source, sqlV1)));
        service.rebuild(List.of(buildRequest("SHOP", source, sqlV2)));

        Map<String, Object> stored = service.getQuery("SHOP/CustomerDao.java#findOne");
        assertThat(stored.get("rawSql")).isEqualTo(sqlV2);
        // v1 referenced one column (id); v2 references three; child rows must be replaced, not appended.
        assertThat((List<?>) stored.get("columns")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(asList(stored, "columns"))
                .extracting(c -> c.get("columnName"))
                .contains("ID", "NAME", "STATUS");
    }

    @Test
    void parseFailureKeepsBusinessMetadata() {
        String unparsable = "SELECT FROM"; // intentionally broken
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

        Map<String, Object> result = service.rebuild(List.of(req));

        assertThat(result.get("parseFailed")).isEqualTo(1);
        Map<String, Object> stored = service.getQuery("SHOP/broken.sql");
        assertThat(stored.get("parseStatus")).isEqualTo("failed");
        assertThat(stored.get("parseError")).isNotNull();
        assertThat(asList(stored, "parameters")).hasSize(1);
        assertThat(asList(stored, "outputs")).hasSize(1);
        assertThat(asList(stored, "fieldUsages")).hasSize(1);
    }

    @Test
    void findQueriesByTableIsCaseInsensitive() {
        service.rebuild(List.of(
                buildSimple("SHOP", "a.sql", "SELECT * FROM Customers WHERE id = 1"),
                buildSimple("SHOP", "b.sql", "SELECT * FROM ORDERS WHERE id = 1")));

        Map<String, Object> byLower = service.findQueriesByTable(null, "customers");
        assertThat(byLower.get("count")).isEqualTo(1);
        assertThat(asList(byLower, "matches"))
                .extracting(r -> r.get("sourcePath")).contains("a.sql");

        Map<String, Object> byUpper = service.findQueriesByTable(null, "ORDERS");
        assertThat(byUpper.get("count")).isEqualTo(1);
    }

    @Test
    void findQueriesByColumnReportsContext() {
        service.rebuild(List.of(buildSimple("SHOP", "a.sql",
                "SELECT name FROM customers WHERE status = 'A' ORDER BY name")));

        Map<String, Object> nameMatches = service.findQueriesByColumn(null, "customers", "name");
        assertThat(asList(nameMatches, "matches"))
                .extracting(r -> r.get("context"))
                .contains("select", "order_by");

        Map<String, Object> statusMatches = service.findQueriesByColumn(null, null, "status");
        assertThat(asList(statusMatches, "matches"))
                .extracting(r -> r.get("context"))
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

        Map<String, Object> all = service.observedRelationships(null, null, 1);
        assertThat(asList(all, "relationships")).hasSize(2);

        Map<String, Object> popular = service.observedRelationships(null, null, 2);
        List<Map<String, Object>> popularRows = asList(popular, "relationships");
        assertThat(popularRows).hasSize(1);
        assertThat(popularRows.get(0).get("support")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> left = (Map<String, Object>) popularRows.get(0).get("left");
        assertThat(left.get("table")).isEqualTo("ORDERS");
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

        Map<String, Object> tags = service.listKnownTags("SHOP");
        assertThat(asList(tags, "tags"))
                .extracting(r -> r.get("tag"))
                .containsExactlyInAnyOrder("customer", "vip");

        Map<String, Object> domains = service.listKnownDomains("SHOP");
        assertThat(asList(domains, "domains"))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.get("domain")).isEqualTo("Customers");
                    assertThat(d.get("count")).isEqualTo(2);
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

        Map<String, Object> result = service.reresolve("SHOP", name -> {
            List<String[]> matches = new java.util.ArrayList<>();
            if ("CUSTOMERS".equals(name) || "ORDERS".equals(name)) {
                matches.add(new String[]{"app", name});
            }
            return matches;
        });

        assertThat(result.get("tablesResolved")).isEqualTo(2);
        assertThat(result.get("tablesAmbiguous")).isEqualTo(0);
        assertThat(result.get("tablesUnresolved")).isEqualTo(0);

        Map<String, Object> stored = service.getQuery("SHOP/q2.sql");
        assertThat(asList(stored, "tables"))
                .allSatisfy(t -> assertThat(t.get("schemaResolved")).isEqualTo("APP"));
        assertThat(asList(stored, "joinPairs")).singleElement()
                .satisfies(jp -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> left = (Map<String, Object>) jp.get("left");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> right = (Map<String, Object>) jp.get("right");
                    assertThat(left.get("schema")).isEqualTo("APP");
                    assertThat(right.get("schema")).isEqualTo("APP");
                });
        assertThat(asList(stored, "columns"))
                .allSatisfy(c -> assertThat(c.get("schemaResolved")).isEqualTo("APP"));
    }

    @Test
    void reresolveMarksAmbiguousWhenMultipleMatchesFound() {
        service.rebuild(List.of(buildSimple("SHOP", "q.sql", "SELECT id FROM orders")));

        Map<String, Object> result = service.reresolve("SHOP", name -> {
            List<String[]> matches = new java.util.ArrayList<>();
            matches.add(new String[]{"app", name});
            matches.add(new String[]{"audit", name});
            return matches;
        });

        assertThat(result.get("tablesAmbiguous")).isEqualTo(1);
        Map<String, Object> stored = service.getQuery("SHOP/q.sql");
        assertThat(asList(stored, "tables")).singleElement()
                .satisfies(t -> {
                    assertThat(t.get("resolutionStatus")).isEqualTo("ambiguous");
                    assertThat(t.get("schemaResolved")).isNull();
                });
    }

    @Test
    void reresolveLeavesUnresolvedWhenLookupReturnsNothing() {
        service.rebuild(List.of(buildSimple("SHOP", "q.sql", "SELECT id FROM orders")));

        Map<String, Object> result = service.reresolve("SHOP", name -> new java.util.ArrayList<>());

        assertThat(result.get("tablesUnresolved")).isEqualTo(1);
        Map<String, Object> stored = service.getQuery("SHOP/q.sql");
        assertThat(asList(stored, "tables")).singleElement()
                .satisfies(t -> assertThat(t.get("resolutionStatus")).isEqualTo("unresolved"));
    }

    @Test
    void getQueryReturnsNotFoundShapeForUnknownUid() {
        Map<String, Object> notFound = service.getQuery("SHOP/missing.sql");
        assertThat(notFound.get("kind")).isEqualTo("not_found");
        assertThat(notFound.get("missing")).isEqualTo("query");
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

    @SuppressWarnings("unchecked")
    private static List<Object> asEvidenceValues(Map<String, Object> root, String key) {
        return ((List<Map<String, Object>>) root.get(key)).stream()
                .map(row -> row.get("value"))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Map<String, Object> root, String key) {
        return (List<Map<String, Object>>) root.get(key);
    }
}
