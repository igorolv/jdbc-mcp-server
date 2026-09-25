package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemalessConnectionsTest {

    /** Arguments of every metadata call the fake driver received, keyed by method name. */
    private final List<String> calls = new ArrayList<>();

    @Test
    void schemaArgumentsNeverReachTheDriver() throws Exception {
        DatabaseMetaData md = wrapped().getMetaData();

        md.getTables(null, "PUBLIC", "FIAS%", null).close();
        md.getColumns(null, "PUBLIC", "FIAS_HOUSE", "%").close();
        md.getImportedKeys(null, "PUBLIC", "FIAS_HOUSE").close();
        md.getCrossReference(null, "PUBLIC", "A", null, "PUBLIC", "B").close();

        assertThat(calls).containsExactly(
                "getTables[null, null, FIAS%, null]",
                "getColumns[null, null, FIAS_HOUSE, %]",
                "getImportedKeys[null, null, FIAS_HOUSE]",
                "getCrossReference[null, null, A, null, null, B]");
    }

    @Test
    void nullSchemaColumnsReadAsTheLogicalSchema() throws Exception {
        try (ResultSet rs = wrapped().getMetaData().getImportedKeys(null, "PUBLIC", "ORDERS")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("PKTABLE_SCHEM")).isEqualTo("PUBLIC");
            assertThat(rs.getString("pktable_schem")).isEqualTo("PUBLIC");
            assertThat(rs.getObject(1)).isEqualTo("PUBLIC");
            assertThat(rs.getString("PKTABLE_NAME")).isEqualTo("CUSTOMERS");
            assertThat(rs.getString("FK_NAME")).isNull();
        }
    }

    @Test
    void listsExactlyOneSchema() throws Exception {
        Connection connection = wrapped();
        assertThat(connection.getSchema()).isEqualTo("PUBLIC");
        try (ResultSet rs = connection.getMetaData().getSchemas()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("TABLE_SCHEM")).isEqualTo("PUBLIC");
            assertThat(rs.next()).isFalse();
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void metadataLeadsBackToTheWrappedConnection() throws Exception {
        Connection connection = wrapped();
        assertThat(connection.getMetaData().getConnection()).isSameAs(connection);
    }

    private Connection wrapped() {
        return SchemalessConnections.wrap(fakeConnection(), "PUBLIC");
    }

    private Connection fakeConnection() {
        DatabaseMetaData md = proxy(DatabaseMetaData.class, (p, method, args) -> {
            calls.add(method.getName() + Arrays.toString(args));
            return fakeKeyRow();
        });
        return proxy(Connection.class, (p, method, args) -> switch (method.getName()) {
            case "getMetaData" -> md;
            case "getSchema" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /** One {@code getImportedKeys} row as Jaybird returns it for Firebird 3: no schema. */
    private static ResultSet fakeKeyRow() {
        List<String> labels = List.of("PKTABLE_SCHEM", "PKTABLE_NAME", "FK_NAME");
        Map<String, String> values = Map.of("PKTABLE_NAME", "CUSTOMERS");
        ResultSetMetaData rsmd = proxy(ResultSetMetaData.class, (p, method, args) -> switch (method.getName()) {
            case "getColumnLabel" -> labels.get((Integer) args[0] - 1);
            default -> throw new UnsupportedOperationException(method.getName());
        });
        int[] cursor = {0};
        return proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
            case "next" -> ++cursor[0] == 1;
            case "getString", "getObject" -> args[0] instanceof Integer i
                    ? values.get(labels.get(i - 1))
                    : values.get(((String) args[0]).toUpperCase());
            case "getMetaData" -> rsmd;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(SchemalessConnectionsTest.class.getClassLoader(),
                new Class<?>[]{type}, handler);
    }
}
