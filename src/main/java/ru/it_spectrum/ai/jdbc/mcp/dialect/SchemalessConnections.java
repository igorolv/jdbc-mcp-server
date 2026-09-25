package ru.it_spectrum.ai.jdbc.mcp.dialect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Presents a database without schemas (Firebird before 6.0) as one logical schema, so the metadata
 * code — which passes a schema to every {@link DatabaseMetaData} call and reads {@code TABLE_SCHEM}
 * back — works unchanged.
 *
 * <p>The wrapped connection differs from the pooled one only in {@link Connection#getMetaData()} and
 * {@link Connection#getSchema()}. Its {@link DatabaseMetaData}:
 * <ul>
 *   <li>replaces every schema argument with {@code null} ("do not filter"), so a logical schema
 *       name never reaches a driver that would not understand it;</li>
 *   <li>optionally pins a {@code null} catalog argument to the connection's own catalog — for
 *       databases like MySQL, where the catalog is the database and {@code null} would list every
 *       database on the server;</li>
 *   <li>reports the logical schema in every {@code *_SCHEM} column the driver leaves {@code null};</li>
 *   <li>lists exactly one schema from {@link DatabaseMetaData#getSchemas()}.</li>
 * </ul>
 */
final class SchemalessConnections {

    /** {@link DatabaseMetaData} methods whose second argument is a schema or schema pattern. */
    private static final Set<String> SCHEMA_AT_INDEX_1 = Set.of(
            "getTables", "getColumns", "getPrimaryKeys", "getIndexInfo",
            "getImportedKeys", "getExportedKeys", "getProcedures", "getProcedureColumns",
            "getFunctions", "getFunctionColumns", "getTablePrivileges", "getColumnPrivileges",
            "getBestRowIdentifier", "getVersionColumns", "getUDTs", "getSuperTypes",
            "getSuperTables", "getAttributes", "getPseudoColumns");

    private SchemalessConnections() {
    }

    static Connection wrap(Connection connection, String logicalSchema) {
        return wrap(connection, logicalSchema, null);
    }

    /** @param catalog catalog to use where callers pass {@code null}; {@code null} leaves them alone */
    static Connection wrap(Connection connection, String logicalSchema, String catalog) {
        return proxy(Connection.class, new ConnectionHandler(connection, logicalSchema, catalog));
    }

    private record ConnectionHandler(Connection target, String logicalSchema, String catalog)
            implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getMetaData" -> proxy(DatabaseMetaData.class,
                        new MetaDataHandler(target.getMetaData(), (Connection) proxy, logicalSchema, catalog));
                case "getSchema" -> logicalSchema;
                default -> call(target, method, args);
            };
        }
    }

    private record MetaDataHandler(DatabaseMetaData target, Connection connection,
                                   String logicalSchema, String catalog) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) {
                return connection;
            }
            if (name.equals("getSchemas")) {
                return singleSchema(logicalSchema);
            }
            if (SCHEMA_AT_INDEX_1.contains(name) && args != null && args.length > 1) {
                args = args.clone();
                args[0] = pinCatalog(args[0]);
                args[1] = null;
            } else if (name.equals("getCrossReference") && args != null && args.length == 6) {
                args = args.clone();
                args[0] = pinCatalog(args[0]);
                args[1] = null;
                args[3] = pinCatalog(args[3]);
                args[4] = null;
            }
            Object result = call(target, method, args);
            if (result instanceof ResultSet rs && method.getReturnType() == ResultSet.class) {
                return proxy(ResultSet.class, new SchemaColumnHandler(rs, logicalSchema));
            }
            return result;
        }

        private Object pinCatalog(Object requested) {
            return requested == null ? catalog : requested;
        }
    }

    /** Fills {@code null} {@code *_SCHEM} columns with the logical schema. */
    private record SchemaColumnHandler(ResultSet target, String logicalSchema) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = call(target, method, args);
            String name = method.getName();
            if (result == null && args != null && args.length == 1
                    && (name.equals("getString") || name.equals("getObject"))
                    && isSchemaColumn(args[0])) {
                return logicalSchema;
            }
            return result;
        }

        private boolean isSchemaColumn(Object column) throws SQLException {
            String label = column instanceof Integer index
                    ? target.getMetaData().getColumnLabel(index)
                    : column instanceof String s ? s : null;
            return label != null && label.toUpperCase(Locale.ROOT).endsWith("_SCHEM");
        }
    }

    /** {@link DatabaseMetaData#getSchemas()} shape: {@code TABLE_SCHEM}, {@code TABLE_CATALOG}. */
    private static ResultSet singleSchema(String logicalSchema) {
        List<String> columns = List.of("TABLE_SCHEM", "TABLE_CATALOG");
        int[] cursor = {0};
        boolean[] closed = {false};
        return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "next" -> ++cursor[0] == 1;
            case "getString", "getObject" -> {
                if (cursor[0] != 1) throw new SQLException("No current row");
                String column = args[0] instanceof Integer i
                        ? columns.get(i - 1)
                        : ((String) args[0]).toUpperCase(Locale.ROOT);
                yield column.equals("TABLE_SCHEM") ? logicalSchema : null;
            }
            case "wasNull" -> false;
            case "close" -> {
                closed[0] = true;
                yield null;
            }
            case "isClosed" -> closed[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "SchemalessConnections.singleSchema(" + logicalSchema + ")";
            default -> throw new SQLFeatureNotSupportedException(method.getName());
        });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(SchemalessConnections.class.getClassLoader(),
                new Class<?>[]{type}, handler);
    }
}
