package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.lineage.QueryLineageResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.usage.ProceduralSqlExtractor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryLineageServiceTest {

    private final QueryAnalysisService analysis = new QueryAnalysisService();
    private final MetadataService metadata = mock(MetadataService.class);
    private final QueryLineageService lineage =
            new QueryLineageService(analysis, metadata, new ProceduralSqlExtractor());

    @Test
    void expandsDatabaseViewToPhysicalTables() throws Exception {
        table("public", "customer_orders_v", "VIEW");
        table("public", "customers", "TABLE");
        table("public", "orders", "TABLE");
        when(metadata.viewDefinition("public", "customer_orders_v")).thenReturn("""
                CREATE VIEW public.customer_orders_v AS
                SELECT c.id, o.id AS order_id
                FROM customers c
                JOIN orders o ON o.customer_id = c.id
                """);

        QueryLineageResult result = lineage.resolve(
                "SELECT * FROM customer_orders_v v", "public", true, false, 5);

        assertThat(result.directObjects())
                .extracting(row -> row.name())
                .containsExactly("customer_orders_v");
        assertThat(result.expandedPhysicalTables())
                .extracting(row -> row.name())
                .containsExactlyInAnyOrder("customers", "orders");
        assertThat(result.expandedPhysicalTables())
                .allSatisfy(row -> assertThat(row.via()).containsExactly("PUBLIC.CUSTOMER_ORDERS_V"));
        assertThat(result.expandedObjects())
                .singleElement()
                .satisfies(view -> assertThat(view.dependsOn())
                        .extracting(dep -> dep.name())
                        .containsExactlyInAnyOrder("customers", "orders"));
    }

    @Test
    void expandsNestedViewsAndKeepsViaPath() throws Exception {
        table("public", "outer_v", "VIEW");
        table("public", "inner_v", "VIEW");
        table("public", "customers", "TABLE");
        when(metadata.viewDefinition("public", "outer_v"))
                .thenReturn("SELECT * FROM inner_v");
        when(metadata.viewDefinition("public", "inner_v"))
                .thenReturn("SELECT * FROM customers");

        QueryLineageResult result = lineage.resolve(
                "SELECT * FROM outer_v", "public", true, false, 5);

        assertThat(result.expandedPhysicalTables())
                .singleElement()
                .satisfies(table -> {
                    assertThat(table.name()).isEqualTo("customers");
                    assertThat(table.via()).containsExactly("PUBLIC.OUTER_V", "PUBLIC.INNER_V");
                    assertThat(table.depth()).isEqualTo(2);
                });
    }

    @Test
    void reportsViewCycles() throws Exception {
        table("public", "a_v", "VIEW");
        table("public", "b_v", "VIEW");
        when(metadata.viewDefinition("public", "a_v")).thenReturn("SELECT * FROM b_v");
        when(metadata.viewDefinition("public", "b_v")).thenReturn("SELECT * FROM a_v");

        QueryLineageResult result = lineage.resolve(
                "SELECT * FROM a_v", "public", true, false, 5);

        assertThat(result.cycles())
                .singleElement()
                .satisfies(cycle -> assertThat(cycle.path())
                        .containsExactly("PUBLIC.A_V", "PUBLIC.B_V", "PUBLIC.A_V"));
    }

    @Test
    void expandsRoutineFunctionBestEffort() throws Exception {
        table("public", "customers", "TABLE");
        table("public", "customer_segments", "TABLE");
        when(metadata.listRoutines(eq("public"), eq("customer_segment"))).thenReturn(routines(
                Map.of("schema", "public", "name", "customer_segment", "kind", "FUNCTION")));
        when(metadata.routineSource("public", "customer_segment")).thenReturn("""
                CREATE FUNCTION customer_segment(id bigint)
                RETURNS text
                AS $$
                SELECT segment FROM customer_segments WHERE customer_id = id;
                $$ LANGUAGE sql
                """);

        QueryLineageResult result = lineage.resolve(
                "SELECT customer_segment(c.id) FROM customers c", "public", true, true, 5);

        assertThat(result.directObjects())
                .extracting(row -> row.name())
                .contains("customers", "customer_segment");
        assertThat(result.expandedPhysicalTables())
                .extracting(row -> row.name())
                .contains("customers", "customer_segments");
        assertThat(result.expandedPhysicalTables().stream()
                .filter(row -> row.name().equals("customer_segments"))
                .findFirst())
                .get()
                .satisfies(row -> assertThat(row.via()).containsExactly("PUBLIC.CUSTOMER_SEGMENT"));
    }

    private void table(String schema, String name, String type) throws Exception {
        when(metadata.listTables(eq(schema), eq(name), any(String[].class)))
                .thenReturn(List.of(new TableEntry(schema, name, type)));
    }

    @SafeVarargs
    private static List<RoutineEntry> routines(Map<String, Object>... rows) {
        List<RoutineEntry> out = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(new RoutineEntry(
                    String.valueOf(row.get("schema")),
                    String.valueOf(row.get("name")),
                    String.valueOf(row.get("kind"))));
        }
        return out;
    }
}
