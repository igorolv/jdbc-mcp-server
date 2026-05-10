package ru.it_spectrum.ai.jdbc.mcp.usage;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.UsageProperties;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseNativeUsageSourceProviderTest {

    @Test
    void loadsViewDefinitionsAsNativeUsageRecords() throws Exception {
        UsageProperties properties = properties(true, true, false, false);
        MetadataService metadata = mock(MetadataService.class);
        when(metadata.defaultSchema()).thenReturn("public");
        when(metadata.schemaViewDefinitions("public")).thenReturn(List.of(Map.of(
                "schema", "public",
                "name", "customer_orders_v",
                "definition", """
                        CREATE VIEW public.customer_orders_v AS
                        SELECT c.id, o.id AS order_id
                        FROM customers c
                        JOIN orders o ON o.customer_id = c.id
                        """)));

        DatabaseNativeUsageSourceProvider provider =
                new DatabaseNativeUsageSourceProvider(properties, metadata);

        List<QueryUsage> records = provider.load();

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.dataSource()).isEqualTo("database");
            assertThat(record.source().kind()).isEqualTo("database-view");
            assertThat(record.source().path()).isEqualTo("native/view/public.customer_orders_v");
            assertThat(record.sql()).startsWith("SELECT c.id");
            assertThat(record.sourceMeta()).containsEntry("native", true);
        });
    }

    @Test
    void extractsSelectLikeRoutineFragments() throws Exception {
        UsageProperties properties = properties(true, false, true, false);
        MetadataService metadata = mock(MetadataService.class);
        when(metadata.defaultSchema()).thenReturn("public");
        when(metadata.listRoutines("public", "%")).thenReturn(new QueryResult(
                List.of("schema", "name", "kind", "language"),
                List.of("TEXT", "TEXT", "TEXT", "TEXT"),
                List.of(Map.of(
                        "schema", "public",
                        "name", "customer_report",
                        "kind", "FUNCTION",
                        "language", "SQL")),
                false,
                1));
        when(metadata.routineSource("public", "customer_report")).thenReturn("""
                CREATE FUNCTION customer_report()
                RETURNS TABLE(id bigint)
                AS $$
                SELECT c.id FROM customers c;
                SELECT o.id FROM orders o;
                $$ LANGUAGE sql
                """);

        DatabaseNativeUsageSourceProvider provider =
                new DatabaseNativeUsageSourceProvider(properties, metadata);

        List<QueryUsage> records = provider.load();

        assertThat(records)
                .extracting(record -> record.source().unit())
                .containsExactly("stmt1", "stmt2");
        assertThat(records)
                .extracting(QueryUsage::sql)
                .containsExactly("SELECT c.id FROM customers c", "SELECT o.id FROM orders o");
        assertThat(records)
                .extracting(record -> record.sourceMeta().get("statementKind"))
                .containsExactly("SELECT", "SELECT");
    }

    @Test
    void extractsOraclePackageBodySubprogramsAsSeparateNativeUsageUnits() throws Exception {
        UsageProperties properties = properties(true, false, true, false);
        MetadataService metadata = mock(MetadataService.class);
        when(metadata.defaultSchema()).thenReturn("APP");
        when(metadata.listRoutines("APP", "%")).thenReturn(new QueryResult(
                List.of("schema", "name", "kind", "language"),
                List.of("TEXT", "TEXT", "TEXT", "TEXT"),
                List.of(Map.of(
                        "schema", "APP",
                        "name", "CUSTOMER_PKG",
                        "kind", "PACKAGE",
                        "language", "PL/SQL")),
                false,
                1));
        when(metadata.routineSource("APP", "CUSTOMER_PKG")).thenReturn("""
                CREATE OR REPLACE PACKAGE BODY customer_pkg AS
                  FUNCTION active_customer_ids RETURN SYS_REFCURSOR IS
                    rc SYS_REFCURSOR;
                  BEGIN
                    OPEN rc FOR
                      SELECT c.id FROM customers c WHERE c.status = 'ACTIVE';
                    RETURN rc;
                  END active_customer_ids;

                  PROCEDURE touch_customer(p_id NUMBER) AS
                  BEGIN
                    UPDATE customers SET touched_at = SYSDATE WHERE id = p_id;
                  END;
                END customer_pkg;
                """);

        DatabaseNativeUsageSourceProvider provider =
                new DatabaseNativeUsageSourceProvider(properties, metadata);

        List<QueryUsage> records = provider.load();

        assertThat(records)
                .extracting(record -> record.source().kind())
                .containsExactly("database-package", "database-package");
        assertThat(records)
                .extracting(record -> record.source().path())
                .containsExactly("native/package/APP.CUSTOMER_PKG", "native/package/APP.CUSTOMER_PKG");
        assertThat(records)
                .extracting(record -> record.source().unit())
                .containsExactly("active_customer_ids.stmt1", "touch_customer.stmt1");
        assertThat(records)
                .extracting(QueryUsage::sql)
                .containsExactly(
                        "SELECT c.id FROM customers c WHERE c.status = 'ACTIVE'",
                        "UPDATE customers SET touched_at = SYSDATE WHERE id = p_id");
        assertThat(records)
                .extracting(record -> record.sourceMeta().get("subprogramName"))
                .containsExactly("active_customer_ids", "touch_customer");
    }

    private static UsageProperties properties(boolean nativeEnabled, boolean views,
                                              boolean routines, boolean triggers) {
        return new UsageProperties(
                true,
                List.of(),
                List.of(),
                views,
                routines,
                triggers,
                100
        );
    }
}
