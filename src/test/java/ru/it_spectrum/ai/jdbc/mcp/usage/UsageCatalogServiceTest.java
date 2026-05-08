package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryAnalysisService;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class UsageCatalogServiceTest {

    @TempDir
    Path tempDir;

    private UsageCatalogService service;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = tempDir.resolve("usage-test.db");
        UsageProperties properties = new UsageProperties(true, dbFile.toString());
        DataSource ds = new UsageDataSourceConfig().usageDataSource(properties);
        service = new UsageCatalogService(properties, ds, new QueryAnalysisService());
    }

    @Test
    void ingestParsedQueryExtractsTablesColumnsAndJoinPairs() {
        String sql = """
                SELECT c.name, o.id
                FROM customers c
                LEFT JOIN orders o ON o.customer_id = c.id
                WHERE c.status = :status
                """;
        IngestPayload.Request req = baseRequest("SHOP", "app/dao/CustomerOrders.java", "findOrders", sql);

        Map<String, Object> result = service.ingest(req);

        assertThat(result.get("uid")).isEqualTo("SHOP/app/dao/CustomerOrders.java#findOrders");
        assertThat(result.get("parseStatus")).isEqualTo("parsed");
        assertThat(result.get("tablesExtracted")).isEqualTo(2);
        assertThat((Integer) result.get("columnsExtracted")).isGreaterThanOrEqualTo(4);
        assertThat(result.get("joinPairsExtracted")).isEqualTo(1);

        Map<String, Object> stored = service.getQuery((String) result.get("uid"));
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
    void ingestStoresParametersOutputsAndFieldUsages() {
        String sql = "SELECT c.name AS cust_name FROM customers c WHERE c.id = :customerId";
        IngestPayload.Request req = new IngestPayload.Request(
                "SHOP",
                new IngestPayload.Source("dao", "CustomerDao.java", "findOne"),
                "Карточка клиента",
                "Customers",
                List.of("customer", "card"),
                sql,
                List.of(new IngestPayload.Param("customerId", "number", null, true,
                        "Идентификатор клиента", "PK таблицы CUSTOMERS")),
                List.of(new IngestPayload.Output("cust_name", "c.name", "Имя клиента", null,
                        List.of(new IngestPayload.OutputColumn(null, "customers", "name")))),
                List.of(new IngestPayload.FieldUsage(
                        "cust_name",
                        "Карточка клиента / Шапка",
                        new IngestPayload.Transformation("identity", "Прямое отображение"),
                        new IngestPayload.Location("ui-label", Map.of("widgetId", "lblName")),
                        List.of("Полное имя"),
                        "high")),
                Map.of("origin", "manual"));

        service.ingest(req);

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
    void reIngestReplacesChildRows() {
        String sqlV1 = "SELECT id FROM customers WHERE id = :id";
        String sqlV2 = "SELECT id, name, status FROM customers WHERE id = :id";
        IngestPayload.Source source = new IngestPayload.Source("dao", "CustomerDao.java", "findOne");

        Map<String, Object> v1 = service.ingest(buildRequest("SHOP", source, sqlV1));
        Map<String, Object> v2 = service.ingest(buildRequest("SHOP", source, sqlV2));

        assertThat(v1.get("uid")).isEqualTo(v2.get("uid"));
        Map<String, Object> stored = service.getQuery((String) v2.get("uid"));
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
        IngestPayload.Request req = new IngestPayload.Request(
                "SHOP",
                new IngestPayload.Source("manual", "broken.sql", null),
                "Сломанный запрос",
                null, null,
                unparsable,
                List.of(new IngestPayload.Param("p", null, null, null, "Параметр", null)),
                List.of(new IngestPayload.Output("a", null, "Колонка А", null, null)),
                List.of(new IngestPayload.FieldUsage(
                        "a", "Где-то",
                        new IngestPayload.Transformation("identity", null),
                        null, null, null)),
                null);

        Map<String, Object> result = service.ingest(req);

        assertThat(result.get("parseStatus")).isEqualTo("failed");
        assertThat(result.get("parseError")).isNotNull();
        Map<String, Object> stored = service.getQuery((String) result.get("uid"));
        assertThat(asList(stored, "parameters")).hasSize(1);
        assertThat(asList(stored, "outputs")).hasSize(1);
        assertThat(asList(stored, "fieldUsages")).hasSize(1);
    }

    @Test
    void findQueriesByTableIsCaseInsensitive() {
        service.ingest(buildSimple("SHOP", "a.sql", "SELECT * FROM Customers WHERE id = 1"));
        service.ingest(buildSimple("SHOP", "b.sql", "SELECT * FROM ORDERS WHERE id = 1"));

        Map<String, Object> byLower = service.findQueriesByTable(null, "customers");
        assertThat(byLower.get("count")).isEqualTo(1);
        assertThat(asList(byLower, "matches"))
                .extracting(r -> r.get("sourcePath")).contains("a.sql");

        Map<String, Object> byUpper = service.findQueriesByTable(null, "ORDERS");
        assertThat(byUpper.get("count")).isEqualTo(1);
    }

    @Test
    void findQueriesByColumnReportsContext() {
        service.ingest(buildSimple("SHOP", "a.sql",
                "SELECT name FROM customers WHERE status = 'A' ORDER BY name"));

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
        service.ingest(buildSimple("SHOP", "q1.sql",
                "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        service.ingest(buildSimple("SHOP", "q2.sql",
                "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE o.id = 1"));
        service.ingest(buildSimple("SHOP", "q3.sql",
                "SELECT * FROM payments p JOIN customers c ON p.customer_id = c.id"));

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
    void deleteBySourceFiltersAreOptional() {
        service.ingest(buildSimple("SHOP", "a.sql", "SELECT id FROM customers"));
        service.ingest(buildSimple("SHOP", "b.sql", "SELECT id FROM orders"));
        service.ingest(buildSimple("OTHER", "a.sql", "SELECT id FROM products"));

        Map<String, Object> deletedNarrow = service.deleteBySource("SHOP", "a.sql", null);
        assertThat(deletedNarrow.get("deleted")).isEqualTo(1);

        Map<String, Object> deletedWide = service.deleteBySource("SHOP", null, null);
        assertThat(deletedWide.get("deleted")).isEqualTo(1); // only b.sql remained for SHOP

        Map<String, Object> remaining = service.listQueries(null, null, null, null, null, null, null, null);
        assertThat(remaining.get("count")).isEqualTo(1);
    }

    @Test
    void listKnownTagsAndDomainsAggregateCounts() {
        IngestPayload.Request req1 = new IngestPayload.Request(
                "SHOP", new IngestPayload.Source("dao", "a.sql", null),
                null, "Customers", List.of("customer"),
                "SELECT 1 FROM dual", null, null, null, null);
        IngestPayload.Request req2 = new IngestPayload.Request(
                "SHOP", new IngestPayload.Source("dao", "b.sql", null),
                null, "Customers", List.of("customer", "vip"),
                "SELECT 1 FROM dual", null, null, null, null);
        service.ingest(req1);
        service.ingest(req2);

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
    void rejectsUnknownTransformationKind() {
        IngestPayload.Request req = new IngestPayload.Request(
                "SHOP", new IngestPayload.Source("manual", "x.sql", null),
                null, null, null,
                "SELECT 1 FROM dual",
                null, null,
                List.of(new IngestPayload.FieldUsage(
                        null, "obj",
                        new IngestPayload.Transformation("WAT", null),
                        null, null, null)),
                null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.ingest(req))
                .withMessageContaining("transformation.kind");
    }

    @Test
    void rejectsUnknownConfidence() {
        IngestPayload.Request req = new IngestPayload.Request(
                "SHOP", new IngestPayload.Source("manual", "x.sql", null),
                null, null, null,
                "SELECT 1 FROM dual",
                null, null,
                List.of(new IngestPayload.FieldUsage(
                        null, "obj",
                        new IngestPayload.Transformation("identity", null),
                        null, null, "supreme")),
                null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.ingest(req))
                .withMessageContaining("confidence");
    }

    @Test
    void observedEdgesReturnsTypedAggregates() {
        service.ingest(buildSimple("SHOP", "q1.sql",
                "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        service.ingest(buildSimple("SHOP", "q2.sql",
                "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));
        service.ingest(buildSimple("SHOP", "q3.sql",
                "SELECT * FROM payments p JOIN customers c ON p.customer_id = c.id"));

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
        service.ingest(buildSimple("SHOP", "q1.sql",
                "SELECT name FROM customers WHERE id = :id"));
        service.ingest(buildSimple("SHOP", "q2.sql",
                "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id"));

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
        service.ingest(buildSimple("SHOP", "q.sql", "SELECT id FROM orders"));

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
        service.ingest(buildSimple("SHOP", "q.sql", "SELECT id FROM orders"));

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

    private static IngestPayload.Request baseRequest(String dataSource, String path, String unit, String sql) {
        return new IngestPayload.Request(
                dataSource,
                new IngestPayload.Source("dao", path, unit),
                null, null, null,
                sql,
                null, null, null, null);
    }

    private static IngestPayload.Request buildRequest(String dataSource, IngestPayload.Source source, String sql) {
        return new IngestPayload.Request(
                dataSource, source, null, null, null, sql,
                null, null, null, null);
    }

    private static IngestPayload.Request buildSimple(String dataSource, String path, String sql) {
        return baseRequest(dataSource, path, null, sql);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Map<String, Object> root, String key) {
        return (List<Map<String, Object>>) root.get(key);
    }
}
