package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.StructureSnapshotProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Constraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.IncomingForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SearchObjectEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SequenceEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.UniqueConstraint;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Collects schema / table / column / index / FK / view / routine / sequence metadata.
 *
 * <p>Uses {@link DatabaseMetaData} where possible (portable), and falls back to dialect-specific
 * queries via {@link SqlDialect} for things the JDBC API does not expose (view source code,
 * routine source, cross-catalog search).
 */
@Service
public class MetadataService {

    private final SqlExecutor executor;
    private final SqlDialect dialect;
    private final JdbcProperties properties;
    private final StructureSnapshotStore store;
    private final SchemaResolver schemaResolver;
    private final StructureSnapshotProperties snapshotProperties;

    public MetadataService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties,
                           StructureSnapshotStore store) {
        this(executor, dialect, properties, store,
                new SchemaResolver(properties, executor, dialect),
                new StructureSnapshotProperties(List.of()));
    }

    @Autowired
    public MetadataService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties,
                           StructureSnapshotStore store, SchemaResolver schemaResolver,
                           StructureSnapshotProperties snapshotProperties) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.store = store;
        this.schemaResolver = schemaResolver;
        this.snapshotProperties = snapshotProperties;
    }

    public String defaultSchema() throws SQLException {
        return resolveSchema(null);
    }

    // ---------- Schemas / tables ----------

    public List<String> listSchemas(boolean includeSystem) throws SQLException {
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = md.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_SCHEM");
                    if (!includeSystem && isSystemSchema(name)) continue;
                    schemas.add(name);
                }
            }
            schemas.sort(String::compareToIgnoreCase);
            return schemas;
        });
    }

    public List<TableEntry> listTables(String schema, String namePattern, String[] types)
            throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        String[] effectiveTypes = types != null && types.length > 0
                ? types
                : new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"};
        return store.listTables(effectiveSchema, namePattern, effectiveTypes,
                () -> listTablesUncached(effectiveSchema, namePattern, effectiveTypes));
    }

    /**
     * Cross-schema lookup of every non-system table/view matching the given name. Returns the
     * raw {@code TABLE_SCHEM} value for each match. Used by the usage-catalog re-resolver to
     * map an unqualified table reference back to its physical schema; bypasses the default-schema
     * defaulting in {@link #resolveSchema} so a {@code null} schema means "search anywhere".
     */
    public List<TableEntry> findTablesByName(String tableName) throws SQLException {
        if (tableName == null || tableName.isBlank()) return List.of();
        return store.findTablesByName(tableName, () -> findTablesByNameLive(tableName));
    }

    private List<TableEntry> findTablesByNameLive(String tableName) throws SQLException {
        String[] effectiveTypes = new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"};
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            List<TableEntry> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, null, tableName, effectiveTypes)) {
                while (rs.next()) {
                    String s = rs.getString("TABLE_SCHEM");
                    if (isSystemSchema(s)) continue;
                    out.add(new TableEntry(s, rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE")));
                }
            }
            return out;
        });
    }

    private List<TableEntry> listTablesUncached(String effectiveSchema, String namePattern,
                                                String[] effectiveTypes) throws SQLException {
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            List<TableEntry> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, effectiveSchema,
                    namePattern == null || namePattern.isBlank() ? "%" : namePattern,
                    effectiveTypes)) {
                while (rs.next()) {
                    String s = rs.getString("TABLE_SCHEM");
                    if (effectiveSchema == null && isSystemSchema(s)) continue;
                    out.add(new TableEntry(
                            s,
                            rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_TYPE"),
                            rs.getString("REMARKS")));
                }
            }
            return out;
        });
    }

    // ---------- describeTable (columns + PK + indexes + FKs) ----------

    public TableDescription describeTable(String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must be provided");
        }
        String effectiveSchema = resolveSchema(schema);
        Map<String, TableDescription> result = describeTables(effectiveSchema, List.of(table));
        return result.get(key(effectiveSchema, table));
    }

    private List<Column> fetchColumns(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        List<Column> cols = new ArrayList<>();
        try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                int ordinal = rs.getInt("ORDINAL_POSITION");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int decimals = rs.getInt("DECIMAL_DIGITS");
                Integer decimalDigits = rs.wasNull() ? null : decimals;
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String autoInc = null;
                try {
                    autoInc = rs.getString("IS_AUTOINCREMENT");
                } catch (SQLException ignore) {
                    // some drivers may not provide this column
                }
                Boolean autoIncrement = "YES".equalsIgnoreCase(autoInc) ? Boolean.TRUE : null;
                cols.add(new Column(name, ordinal, typeName, size, decimalDigits, nullable,
                        rs.getString("COLUMN_DEF"), rs.getString("REMARKS"), autoIncrement));
            }
        }
        cols.sort(Comparator.comparingInt(Column::ordinalPosition));
        return cols;
    }

    private static List<String> readColumns(ResultSet rs) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String label = md.getColumnLabel(i);
            cols.add(label != null && !label.isBlank() ? label : md.getColumnName(i));
        }
        return cols;
    }

    private static Map<String, Object> readRow(ResultSet rs, List<String> columns) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>(columns.size() * 2);
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), rs.getObject(i + 1));
        }
        return row;
    }

    private PrimaryKey fetchPrimaryKey(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        List<String> cols = new ArrayList<>();
        String name = null;
        try (ResultSet rs = md.getPrimaryKeys(null, schema, table)) {
            // ORDER BY KEY_SEQ to preserve column order
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Object[]{rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"), rs.getString("PK_NAME")});
            }
            rows.sort(Comparator.comparingInt(a -> (Short) a[0]));
            for (Object[] r : rows) {
                cols.add((String) r[1]);
                if (name == null) name = (String) r[2];
            }
        }
        if (cols.isEmpty()) return null;
        return new PrimaryKey(name, cols);
    }

    private List<Index> fetchIndexes(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        record Pending(String name, boolean unique, List<String> columns) {}
        Map<String, Pending> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, false, false)) {
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idxName == null) continue;
                boolean unique = !rs_unchecked(rs, "NON_UNIQUE", true);
                Pending p = byName.computeIfAbsent(idxName, k -> new Pending(k, unique, new ArrayList<>()));
                if (col != null) p.columns().add(col);
            }
        }
        List<Index> out = new ArrayList<>(byName.size());
        for (Pending p : byName.values()) out.add(new Index(p.name(), p.unique(), p.columns()));
        return out;
    }

    private boolean rs_unchecked(ResultSet rs, String col, boolean fallback) {
        try {
            return rs.getBoolean(col);
        } catch (SQLException e) {
            return fallback;
        }
    }

    private List<ForeignKey> fetchImportedKeys(Connection conn, DatabaseMetaData md, String schema, String table)
            throws SQLException {
        String sql = dialect.importedKeysQuery();
        if (sql != null) {
            return fetchImportedKeysSql(conn, sql, schema, table);
        }
        return fetchImportedKeysJdbc(md, schema, table);
    }

    private List<ForeignKey> fetchImportedKeysSql(Connection conn, String sql, String schema, String table)
            throws SQLException {
        record Pending(String name, List<String> columns, List<String> referencedColumns,
                       String[] referencedSchema, String[] referencedTable) {}
        Map<String, Pending> byName = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setQueryTimeout(properties.queryTimeoutSeconds());
            ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    if (fkName == null) fkName = "fk_anon_" + rs.getString("FKCOLUMN_NAME");
                    Pending p = byName.computeIfAbsent(fkName, k -> new Pending(
                            k, new ArrayList<>(), new ArrayList<>(), new String[1], new String[1]));
                    p.referencedSchema()[0] = rs.getString("PKTABLE_SCHEM");
                    p.referencedTable()[0] = rs.getString("PKTABLE_NAME");
                    p.columns().add(rs.getString("FKCOLUMN_NAME"));
                    p.referencedColumns().add(rs.getString("PKCOLUMN_NAME"));
                }
            }
        }
        List<ForeignKey> out = new ArrayList<>(byName.size());
        for (Pending p : byName.values()) {
            out.add(new ForeignKey(p.name(), p.columns(),
                    p.referencedSchema()[0], p.referencedTable()[0], p.referencedColumns()));
        }
        return out;
    }

    private List<ForeignKey> fetchImportedKeysJdbc(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        record Pending(String name, List<String> columns, List<String> referencedColumns,
                       String[] referencedSchema, String[] referencedTable) {}
        Map<String, Pending> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getImportedKeys(null, schema, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                if (fkName == null) fkName = "fk_anon_" + rs.getString("FKCOLUMN_NAME");
                Pending p = byName.computeIfAbsent(fkName, k -> new Pending(
                        k, new ArrayList<>(), new ArrayList<>(), new String[1], new String[1]));
                p.referencedSchema()[0] = rs.getString("PKTABLE_SCHEM");
                p.referencedTable()[0] = rs.getString("PKTABLE_NAME");
                p.columns().add(rs.getString("FKCOLUMN_NAME"));
                p.referencedColumns().add(rs.getString("PKCOLUMN_NAME"));
            }
        }
        List<ForeignKey> out = new ArrayList<>(byName.size());
        for (Pending p : byName.values()) {
            out.add(new ForeignKey(p.name(), p.columns(),
                    p.referencedSchema()[0], p.referencedTable()[0], p.referencedColumns()));
        }
        return out;
    }

    private List<IncomingForeignKey> fetchExportedKeys(Connection conn, DatabaseMetaData md, String schema, String table)
            throws SQLException {
        String sql = dialect.exportedKeysQuery();
        if (sql != null) {
            return fetchExportedKeysSql(conn, sql, schema, table);
        }
        return fetchExportedKeysJdbc(md, schema, table);
    }

    private List<IncomingForeignKey> fetchExportedKeysSql(Connection conn, String sql, String schema, String table)
            throws SQLException {
        record Pending(String name, List<String> fromColumns, List<String> toColumns,
                       String[] fromSchema, String[] fromTable) {}
        Map<String, Pending> byName = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setQueryTimeout(properties.queryTimeoutSeconds());
            ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    if (fkName == null) fkName = "fk_anon_" + rs.getString("FKCOLUMN_NAME");
                    Pending p = byName.computeIfAbsent(fkName, k -> new Pending(
                            k, new ArrayList<>(), new ArrayList<>(), new String[1], new String[1]));
                    p.fromSchema()[0] = rs.getString("FKTABLE_SCHEM");
                    p.fromTable()[0] = rs.getString("FKTABLE_NAME");
                    p.fromColumns().add(rs.getString("FKCOLUMN_NAME"));
                    p.toColumns().add(rs.getString("PKCOLUMN_NAME"));
                }
            }
        }
        List<IncomingForeignKey> out = new ArrayList<>(byName.size());
        for (Pending p : byName.values()) {
            out.add(new IncomingForeignKey(p.name(),
                    p.fromSchema()[0], p.fromTable()[0], p.fromColumns(), p.toColumns()));
        }
        return out;
    }

    private List<IncomingForeignKey> fetchExportedKeysJdbc(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        record Pending(String name, List<String> fromColumns, List<String> toColumns,
                       String[] fromSchema, String[] fromTable) {}
        Map<String, Pending> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getExportedKeys(null, schema, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                if (fkName == null) fkName = "fk_anon_" + rs.getString("FKCOLUMN_NAME");
                Pending p = byName.computeIfAbsent(fkName, k -> new Pending(
                        k, new ArrayList<>(), new ArrayList<>(), new String[1], new String[1]));
                p.fromSchema()[0] = rs.getString("FKTABLE_SCHEM");
                p.fromTable()[0] = rs.getString("FKTABLE_NAME");
                p.fromColumns().add(rs.getString("FKCOLUMN_NAME"));
                p.toColumns().add(rs.getString("PKCOLUMN_NAME"));
            }
        }
        List<IncomingForeignKey> out = new ArrayList<>(byName.size());
        for (Pending p : byName.values()) {
            out.add(new IncomingForeignKey(p.name(),
                    p.fromSchema()[0], p.fromTable()[0], p.fromColumns(), p.toColumns()));
        }
        return out;
    }

    /**
     * Bulk-load structural metadata for a collection of table names (same schema). Uses
     * schema-level bulk queries (≈10 round trips total, regardless of table count) — much faster
     * than calling {@link #describeTable} in a loop. Checks the structure snapshot first and only
     * loads uncached tables. All loaded results are persisted to the snapshot for subsequent fast
     * lookup.
     */
    public Map<String, TableDescription> describeTables(String schema, Collection<String> tableNames)
            throws SQLException {
        if (tableNames == null || tableNames.isEmpty()) return Map.of();
        String effectiveSchema = resolveSchema(schema);

        List<String> distinct = tableNames.stream()
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
        if (distinct.isEmpty()) return Map.of();

        Map<String, TableDescription> result = new LinkedHashMap<>();
        List<String> uncached = new ArrayList<>();
        for (String tn : distinct) {
            TableDescription cached = store.peekDescribeTable(effectiveSchema, tn);
            if (cached != null) {
                result.put(key(effectiveSchema, tn), cached);
            } else {
                uncached.add(tn);
            }
        }

        if (!uncached.isEmpty()) {
            Map<String, TableDescription> loaded = describeListedTables(effectiveSchema, new LinkedHashSet<>(uncached));
            store.saveAll(loaded.values());
            result.putAll(loaded);
        }

        return result;
    }

    private Map<String, TableDescription> describeListedTables(
            String effectiveSchema,
            Set<String> tableNames) throws SQLException {
        if (tableNames.isEmpty()) return Map.of();

        Map<String, TableDescription> result = executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();

            Map<String, TableEntry> tableEntryByName = new LinkedHashMap<>();
            String[] allTypes = {"TABLE", "VIEW", "MATERIALIZED VIEW", "SYNONYM", "ALIAS", "SYSTEM TABLE", "GLOBAL TEMPORARY"};
            for (String name : tableNames) {
                try (ResultSet rs = md.getTables(null, effectiveSchema, name, allTypes)) {
                    if (rs.next()) {
                        tableEntryByName.put(name, new TableEntry(
                                effectiveSchema, name,
                                rs.getString("TABLE_TYPE"),
                                rs.getString("REMARKS")));
                    } else {
                        tableEntryByName.put(name, new TableEntry(effectiveSchema, name, null, null));
                    }
                }
            }

            Map<String, List<Column>> columnsMap = fetchColumnsForTables(md, effectiveSchema, tableNames);

            Map<String, List<Index>> indexesMap = new LinkedHashMap<>();
            Map<String, List<UniqueConstraint>> uniqueMap = new LinkedHashMap<>();
            fetchIndexesForTablesBulk(conn, md, effectiveSchema, tableNames, indexesMap, uniqueMap);

            Map<String, List<Constraint>> constraintsMap = fetchConstraintsForTablesBulk(conn, effectiveSchema, tableNames);
            Map<String, PrimaryKey> pkMap = primaryKeysFromConstraints(constraintsMap);
            Map<String, List<ForeignKey>> fkMap = foreignKeysFromConstraints(effectiveSchema, constraintsMap);
            Map<String, List<IncomingForeignKey>> exportedMap =
                    incomingForeignKeysForReferencedTables(conn, effectiveSchema, tableNames);
            Map<String, List<Trigger>> triggersMap = fetchTriggersForTablesBulk(conn, effectiveSchema, tableNames);

            Map<String, TableDescription> descMap = new LinkedHashMap<>();
            for (String t : tableNames) {
                TableEntry te = tableEntryByName.get(t);
                List<Column> cols = columnsMap.getOrDefault(t, List.of());
                PrimaryKey pk = pkMap.get(t);
                List<UniqueConstraint> unique = uniqueMap.getOrDefault(t, List.of());
                List<Index> indexes = indexesMap.getOrDefault(t, List.of());
                List<ForeignKey> fks = fkMap.getOrDefault(t, List.of());
                List<IncomingForeignKey> refs = exportedMap.getOrDefault(t, List.of());
                List<Constraint> cons = constraintsMap.getOrDefault(t, List.of());
                Map<String, List<String>> allowedValues = extractAllowedValues(cons);
                List<Trigger> triggers = triggersMap.getOrDefault(t, List.of());

                descMap.put(key(te.schema(), t), new TableDescription(
                        te.schema(), t, te.type(), te.remarks(),
                        cols, pk, unique, indexes, fks, refs,
                        cons, allowedValues, triggers));
            }
            return descMap;
        });
        return result;
    }

    private Map<String, List<Column>> fetchColumnsForTables(DatabaseMetaData md, String schema,
                                                            Set<String> tableNames)
            throws SQLException {
        if (useOracleSelectedBulk(tableNames)) {
            return fetchOracleColumnsForTables(md.getConnection(), schema, tableNames);
        }
        if (tableNames.size() > 100) {
            Map<String, List<Column>> all = fetchAllColumns(md, schema);
            all.keySet().removeIf(table -> !containsTableName(tableNames, table));
            return all;
        }

        Map<String, List<Column>> byTable = new LinkedHashMap<>();
        for (String table : tableNames) {
            List<Column> cols = fetchColumns(md, schema, table);
            if (!cols.isEmpty()) {
                byTable.put(table, cols);
            }
        }
        return byTable;
    }

    private Map<String, List<Column>> fetchOracleColumnsForTables(Connection conn, String schema,
                                                                    Set<String> tableNames)
            throws SQLException {
        Map<String, List<Column>> byTable = new LinkedHashMap<>();
        for (Set<String> chunk : partition(tableNames, ORACLE_IN_LIST_LIMIT)) {
            String sql = """
                    SELECT c.table_name,
                           c.column_name,
                           c.column_id AS ordinal_position,
                           c.data_type AS type_name,
                           COALESCE(c.data_precision, c.char_length, c.data_length) AS column_size,
                           c.data_scale AS decimal_digits,
                           c.nullable AS is_nullable,
                           co.comments AS "comment",
                           CASE
                               WHEN c.default_length IS NULL THEN NULL
                               ELSE EXTRACTVALUE(
                                       DBMS_XMLGEN.GETXMLTYPE(
                                           'select data_default from user_tab_columns where table_name = '''
                                           || c.table_name
                                           || ''' and column_name = '''
                                           || c.column_name
                                           || '''' ),
                                       '//text()' )
                           END AS default_value
                    FROM all_tab_columns c
                    LEFT JOIN all_col_comments co
                      ON co.owner = c.owner
                     AND co.table_name = c.table_name
                     AND co.column_name = c.column_name
                    WHERE c.owner = UPPER(?)
                      AND c.table_name IN (%s)
                    ORDER BY c.table_name, c.column_id
                    """.formatted(upperPlaceholders(chunk.size()));
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                bindSchemaAndTables(ps, schema, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("table_name");
                        String name = rs.getString("column_name");
                        int ordinal = rs.getInt("ordinal_position");
                        String typeName = rs.getString("type_name");
                        int size = rs.getInt("column_size");
                        int decimals = rs.getInt("decimal_digits");
                        Integer decimalDigits = rs.wasNull() ? null : decimals;
                        boolean nullable = "Y".equalsIgnoreCase(rs.getString("is_nullable"));
                        String comment = rs.getString("comment");
                        String defaultVal = rs.getString("default_value");
                        byTable.computeIfAbsent(table, ignored -> new ArrayList<>())
                                .add(new Column(name, ordinal, typeName, size, decimalDigits,
                                        nullable, defaultVal, comment, null));
                    }
                }
            }
        }
        return byTable;
    }

    private static boolean containsTableName(Set<String> tableNames, String table) {
        if (tableNames.contains(table)) return true;
        for (String name : tableNames) {
            if (name != null && name.equalsIgnoreCase(table)) return true;
        }
        return false;
    }

    private static final int ORACLE_IN_LIST_LIMIT = 1_000;

    private boolean useOracleSelectedBulk(Set<String> tableNames) {
        return dialect.kind() == DatabaseKind.ORACLE && !tableNames.isEmpty();
    }

    private static List<Set<String>> partition(Set<String> tableNames, int maxSize) {
        if (tableNames.size() <= maxSize) {
            return List.of(tableNames);
        }
        List<String> names = new ArrayList<>(tableNames);
        List<Set<String>> chunks = new ArrayList<>();
        for (int i = 0; i < names.size(); i += maxSize) {
            chunks.add(new LinkedHashSet<>(
                    names.subList(i, Math.min(i + maxSize, names.size()))));
        }
        return chunks;
    }

    private static String upperPlaceholders(int count) {
        return String.join(", ", Collections.nCopies(count, "UPPER(?)"));
    }

    private static void bindSchemaAndTables(PreparedStatement ps, String schema, Set<String> tableNames)
            throws SQLException {
        ps.setString(1, schema == null ? "" : schema);
        bindTables(ps, tableNames, 2);
    }

    private static void bindTables(PreparedStatement ps, Set<String> tableNames, int startIndex)
            throws SQLException {
        int index = startIndex;
        for (String table : tableNames) {
            ps.setString(index++, table);
        }
    }

    private boolean isCurrentOracleUserSchema(Connection conn, String schema) throws SQLException {
        if (dialect.kind() != DatabaseKind.ORACLE || schema == null || schema.isBlank()) return false;
        String user = conn.getMetaData().getUserName();
        return user != null && schema.equalsIgnoreCase(user);
    }

    private Map<String, List<Column>> fetchAllColumns(DatabaseMetaData md, String schema)
            throws SQLException {
        Map<String, List<Column>> byTable = new LinkedHashMap<>();
        try (ResultSet rs = md.getColumns(null, schema, "%", "%")) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                String name = rs.getString("COLUMN_NAME");
                int ordinal = rs.getInt("ORDINAL_POSITION");
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int decimals = rs.getInt("DECIMAL_DIGITS");
                Integer decimalDigits = rs.wasNull() ? null : decimals;
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                String autoInc = null;
                try {
                    autoInc = rs.getString("IS_AUTOINCREMENT");
                } catch (SQLException ignore) {
                }
                Boolean autoIncrement = "YES".equalsIgnoreCase(autoInc) ? Boolean.TRUE : null;
                byTable.computeIfAbsent(table, k -> new ArrayList<>())
                        .add(new Column(name, ordinal, typeName, size, decimalDigits,
                                nullable, rs.getString("COLUMN_DEF"), rs.getString("REMARKS"),
                                autoIncrement));
            }
        }
        for (List<Column> cols : byTable.values()) {
            cols.sort(Comparator.comparingInt(Column::ordinalPosition));
        }
        return byTable;
    }

    private void fetchAllIndexesBulk(Connection conn, String schema,
                                     Map<String, List<Index>> indexesMap,
                                     Map<String, List<UniqueConstraint>> uniqueMap) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(dialect.indexStatsQuery(),
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setQueryTimeout(properties.queryTimeoutSeconds());
            ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
            ps.setString(1, schema == null ? "" : schema);
            ps.setObject(2, null);
            ps.setObject(3, null);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String idxName = rs.getString("index_name");
                    if (table == null || idxName == null) continue;
                    boolean unique = toBool(rs.getObject("is_unique"));
                    List<String> columns = splitCsv(rs.getObject("columns"));
                    indexesMap.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(new Index(idxName, unique, columns));
                    if (unique) {
                        uniqueMap.computeIfAbsent(table, k -> new ArrayList<>())
                                .add(new UniqueConstraint(idxName, columns));
                    }
                }
            }
            return;
        } catch (SQLException ignored) {
            // Fall back below. Some drivers are stricter about catalog views under low privileges.
        }

        DatabaseMetaData md = conn.getMetaData();
        List<TableEntry> tables = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(new TableEntry(
                        rs.getString("TABLE_SCHEM"),
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE"),
                        rs.getString("REMARKS")));
            }
        }
        for (TableEntry table : tables) {
            List<Index> indexes = fetchIndexes(md, table.schema(), table.name());
            indexesMap.put(table.name(), indexes);
            List<UniqueConstraint> unique = new ArrayList<>();
            for (Index index : indexes) {
                if (index.unique()) {
                    unique.add(new UniqueConstraint(index.name(), index.columns()));
                }
            }
            uniqueMap.put(table.name(), unique);
        }
    }

    private void fetchIndexesForTablesBulk(Connection conn,
                                           DatabaseMetaData md,
                                           String schema,
                                           Set<String> tableNames,
                                           Map<String, List<Index>> indexesMap,
                                           Map<String, List<UniqueConstraint>> uniqueMap)
            throws SQLException {
        if (useOracleSelectedBulk(tableNames)) {
            fetchOracleIndexesForTables(conn, schema, tableNames, indexesMap, uniqueMap);
            return;
        }
        if (tableNames.size() > 100) {
            fetchAllIndexesBulk(conn, schema, indexesMap, uniqueMap);
            indexesMap.keySet().removeIf(table -> !containsTableName(tableNames, table));
            uniqueMap.keySet().removeIf(table -> !containsTableName(tableNames, table));
            return;
        }

        String sql = dialect.indexStatsQuery();
        for (String table : tableNames) {
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                ps.setString(1, schema == null ? "" : schema);
                ps.setString(2, table);
                ps.setString(3, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rowTable = rs.getString("table_name");
                        String idxName = rs.getString("index_name");
                        if (rowTable == null || idxName == null) continue;
                        boolean unique = toBool(rs.getObject("is_unique"));
                        List<String> columns = splitCsv(rs.getObject("columns"));
                        indexesMap.computeIfAbsent(rowTable, ignored -> new ArrayList<>())
                                .add(new Index(idxName, unique, columns));
                        if (unique) {
                            uniqueMap.computeIfAbsent(rowTable, ignored -> new ArrayList<>())
                                    .add(new UniqueConstraint(idxName, columns));
                        }
                    }
                }
            } catch (SQLException ignored) {
                List<Index> indexes = fetchIndexes(md, schema, table);
                indexesMap.put(table, indexes);
                List<UniqueConstraint> unique = new ArrayList<>();
                for (Index index : indexes) {
                    if (index.unique()) {
                        unique.add(new UniqueConstraint(index.name(), index.columns()));
                    }
                }
                uniqueMap.put(table, unique);
            }
        }
    }

    private void fetchOracleIndexesForTables(Connection conn,
                                              String schema,
                                              Set<String> tableNames,
                                              Map<String, List<Index>> indexesMap,
                                              Map<String, List<UniqueConstraint>> uniqueMap)
            throws SQLException {
        for (Set<String> chunk : partition(tableNames, ORACLE_IN_LIST_LIMIT)) {
            String sql = """
                    SELECT i.table_name AS table_name,
                           i.index_name AS index_name,
                           CASE WHEN i.uniqueness = 'UNIQUE' THEN 'Y' ELSE 'N' END AS is_unique,
                           LISTAGG(c.column_name, ',') WITHIN GROUP (ORDER BY c.column_position) AS columns
                    FROM all_indexes i
                    LEFT JOIN all_ind_columns c
                      ON c.index_owner = i.owner
                     AND c.index_name = i.index_name
                    WHERE i.owner = UPPER(?)
                      AND i.table_name IN (%s)
                    GROUP BY i.table_name, i.index_name, i.uniqueness
                    ORDER BY i.table_name, i.index_name
                    """.formatted(upperPlaceholders(chunk.size()));
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                bindSchemaAndTables(ps, schema, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("table_name");
                        String idxName = rs.getString("index_name");
                        if (table == null || idxName == null) continue;
                        boolean unique = toBool(rs.getObject("is_unique"));
                        List<String> columns = splitCsv(rs.getObject("columns"));
                        indexesMap.computeIfAbsent(table, ignored -> new ArrayList<>())
                                .add(new Index(idxName, unique, columns));
                        if (unique) {
                            uniqueMap.computeIfAbsent(table, ignored -> new ArrayList<>())
                                    .add(new UniqueConstraint(idxName, columns));
                        }
                    }
                }
            }
        }
    }

    private Map<String, PrimaryKey> primaryKeysFromConstraints(
            Map<String, List<Constraint>> constraintsMap) {
        Map<String, PrimaryKey> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Constraint>> entry : constraintsMap.entrySet()) {
            for (Constraint constraint : entry.getValue()) {
                if ("PRIMARY_KEY".equalsIgnoreCase(constraint.type())) {
                    out.put(entry.getKey(), new PrimaryKey(constraint.name(), constraint.columns()));
                    break;
                }
            }
        }
        return out;
    }

    private Map<String, List<ForeignKey>> foreignKeysFromConstraints(
            String schema, Map<String, List<Constraint>> constraintsMap) {
        Map<String, List<ForeignKey>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Constraint>> entry : constraintsMap.entrySet()) {
            for (Constraint constraint : entry.getValue()) {
                if (!"FOREIGN_KEY".equalsIgnoreCase(constraint.type())) continue;
                if (constraint.referencedTable() == null || constraint.referencedColumns() == null) continue;
                out.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .add(new ForeignKey(constraint.name(), constraint.columns(),
                                constraint.referencedSchema() == null ? schema : constraint.referencedSchema(),
                                constraint.referencedTable(), constraint.referencedColumns()));
            }
        }
        return out;
    }

    private Map<String, List<IncomingForeignKey>> incomingForeignKeysFromConstraints(
            String schema, Map<String, List<Constraint>> constraintsMap) {
        Map<String, List<IncomingForeignKey>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Constraint>> entry : constraintsMap.entrySet()) {
            String childTable = entry.getKey();
            for (Constraint constraint : entry.getValue()) {
                if (!"FOREIGN_KEY".equalsIgnoreCase(constraint.type())) continue;
                if (constraint.referencedTable() == null || constraint.referencedColumns() == null) continue;
                out.computeIfAbsent(constraint.referencedTable(), k -> new ArrayList<>())
                        .add(new IncomingForeignKey(constraint.name(), schema, childTable,
                                constraint.columns(), constraint.referencedColumns()));
            }
        }
        return out;
    }

    private Map<String, List<IncomingForeignKey>> incomingForeignKeysForReferencedTables(
            Connection conn,
            String schema,
            Set<String> referencedTableNames) throws SQLException {
        Map<String, List<Constraint>> allConstraints = fetchAllConstraintsBulk(conn, schema);
        Map<String, List<IncomingForeignKey>> incoming =
                incomingForeignKeysFromConstraints(schema, allConstraints);
        incoming.keySet().removeIf(table -> !containsTableName(referencedTableNames, table));
        return incoming;
    }

    private Map<String, List<IncomingForeignKey>> incomingForeignKeysFromForeignKeys(
            String schema, Map<String, List<ForeignKey>> foreignKeysMap) {
        Map<String, List<IncomingForeignKey>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<ForeignKey>> entry : foreignKeysMap.entrySet()) {
            String childTable = entry.getKey();
            for (ForeignKey foreignKey : entry.getValue()) {
                if (foreignKey.referencedTable() == null) continue;
                out.computeIfAbsent(foreignKey.referencedTable(), ignored -> new ArrayList<>())
                        .add(new IncomingForeignKey(foreignKey.name(), schema, childTable,
                                foreignKey.columns(), foreignKey.referencedColumns()));
            }
        }
        return out;
    }

    private record KeyMetadata(
            Map<String, PrimaryKey> primaryKeys,
            Map<String, List<ForeignKey>> foreignKeys,
            Map<String, List<Constraint>> constraints
    ) {}

    private Map<String, List<Constraint>> fetchAllConstraintsBulk(Connection conn, String schema) throws SQLException {
        String sql = dialect.schemaConstraintsQuery();
        if (sql == null) {
            Map<String, List<Constraint>> out = new LinkedHashMap<>();
            String tableSql = dialect.tableConstraintsQuery();
            if (tableSql == null) return out;
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getTables(null, schema, "%",
                    new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    List<Constraint> cons = fetchConstraints(conn, tableSql, schema, table);
                    if (!cons.isEmpty()) out.put(table, cons);
                }
            }
            return out;
        }
        Map<String, List<Constraint>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setQueryTimeout(properties.queryTimeoutSeconds());
            ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
            ps.setString(1, schema == null ? "" : schema);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> columns = readColumns(rs);
                while (rs.next()) {
                    Map<String, Object> row = readRow(rs, columns);
                    String table = asString(getCI(row, "table_name"));
                    if (table == null || table.isBlank()) continue;
                    out.computeIfAbsent(table, k -> new ArrayList<>()).add(constraintFromRow(row));
                }
            }
        }
        return out;
    }

    private Map<String, List<Constraint>> fetchConstraintsForTablesBulk(
            Connection conn,
            String schema,
            Set<String> tableNames) throws SQLException {
        if (tableNames.size() > 100 || dialect.tableConstraintsQuery() == null) {
            Map<String, List<Constraint>> all = fetchAllConstraintsBulk(conn, schema);
            all.keySet().removeIf(table -> !containsTableName(tableNames, table));
            return all;
        }

        Map<String, List<Constraint>> out = new LinkedHashMap<>();
        String tableSql = dialect.tableConstraintsQuery();
        for (String table : tableNames) {
            List<Constraint> cons = fetchConstraints(conn, tableSql, schema, table);
            if (!cons.isEmpty()) {
                out.put(table, cons);
            }
        }
        return out;
    }

    private KeyMetadata fetchOracleKeyMetadataForTables(Connection conn, String schema,
                                                        Set<String> tableNames)
            throws SQLException {
        if (!isCurrentOracleUserSchema(conn, schema)) {
            return fetchOracleKeyMetadataPerTable(conn, schema, tableNames);
        }
        Map<String, PrimaryKey> primaryKeys = fetchOraclePrimaryKeysForTables(conn, schema, tableNames);
        Map<String, List<ForeignKey>> foreignKeys = fetchOracleForeignKeysForTables(conn, schema, tableNames);
        Map<String, List<Constraint>> constraints = new LinkedHashMap<>();
        for (Map.Entry<String, PrimaryKey> entry : primaryKeys.entrySet()) {
            PrimaryKey pk = entry.getValue();
            constraints.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                    .add(new Constraint(pk.name(), "PRIMARY_KEY", pk.columns(),
                            null, null, null, null, null, null));
        }
        for (Map.Entry<String, List<ForeignKey>> entry : foreignKeys.entrySet()) {
            for (ForeignKey fk : entry.getValue()) {
                constraints.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
                        .add(new Constraint(fk.name(), "FOREIGN_KEY", fk.columns(),
                                null, null, null,
                                fk.referencedSchema(), fk.referencedTable(), fk.referencedColumns()));
            }
        }
        return new KeyMetadata(primaryKeys, foreignKeys, constraints);
    }

    private KeyMetadata fetchOracleKeyMetadataPerTable(Connection conn, String schema, Set<String> tableNames)
            throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        Map<String, PrimaryKey> primaryKeys = new LinkedHashMap<>();
        Map<String, List<ForeignKey>> foreignKeys = new LinkedHashMap<>();
        Map<String, List<Constraint>> constraints = new LinkedHashMap<>();
        String importedKeysSql = dialect.importedKeysQuery();

        for (String table : tableNames) {
            PrimaryKey pk = fetchPrimaryKey(md, schema, table);
            if (pk != null) {
                primaryKeys.put(table, pk);
                constraints.computeIfAbsent(table, ignored -> new ArrayList<>())
                        .add(new Constraint(pk.name(), "PRIMARY_KEY", pk.columns(),
                                null, null, null, null, null, null));
            }
            List<ForeignKey> fks = importedKeysSql == null
                    ? fetchImportedKeysJdbc(md, schema, table)
                    : fetchImportedKeysSql(conn, importedKeysSql, schema, table);
            if (!fks.isEmpty()) {
                foreignKeys.put(table, fks);
                for (ForeignKey fk : fks) {
                    constraints.computeIfAbsent(table, ignored -> new ArrayList<>())
                            .add(new Constraint(fk.name(), "FOREIGN_KEY", fk.columns(),
                                    null, null, null,
                                    fk.referencedSchema(), fk.referencedTable(), fk.referencedColumns()));
                }
            }
        }
        return new KeyMetadata(primaryKeys, foreignKeys, constraints);
    }

private Map<String, PrimaryKey> fetchOraclePrimaryKeysForTables(Connection conn, String schema,
                                                                     Set<String> tableNames)
            throws SQLException {
        if (isCurrentOracleUserSchema(conn, schema)) {
            return fetchOracleUserPrimaryKeysForTables(conn, tableNames);
        }
        record Pending(String name, List<String> columns) {}
        Map<String, Pending> byTable = new LinkedHashMap<>();
        for (Set<String> chunk : partition(tableNames, ORACLE_IN_LIST_LIMIT)) {
            String sql = """
                    SELECT c.table_name,
                           c.constraint_name,
                           cc.column_name
                    FROM all_constraints c
                    JOIN all_cons_columns cc
                      ON cc.owner = c.owner
                     AND cc.table_name = c.table_name
                     AND cc.constraint_name = c.constraint_name
                    WHERE c.owner = UPPER(?)
                      AND c.table_name IN (%s)
                      AND c.constraint_type = 'P'
                    ORDER BY c.table_name, cc.position
                    """.formatted(upperPlaceholders(chunk.size()));
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                bindSchemaAndTables(ps, schema, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("table_name");
                        String name = rs.getString("constraint_name");
                        String column = rs.getString("column_name");
                        if (table == null || column == null) continue;
                        byTable.computeIfAbsent(table, ignored -> new Pending(name, new ArrayList<>()))
                                .columns().add(column);
                    }
                }
            }
        }
        Map<String, PrimaryKey> out = new LinkedHashMap<>();
        for (Map.Entry<String, Pending> entry : byTable.entrySet()) {
            out.put(entry.getKey(), new PrimaryKey(entry.getValue().name(), entry.getValue().columns()));
        }
        return out;
    }

private Map<String, PrimaryKey> fetchOracleUserPrimaryKeysForTables(Connection conn,
                                                                         Set<String> tableNames)
            throws SQLException {
        record Pending(String name, List<String> columns) {}
        Map<String, Pending> byTable = new LinkedHashMap<>();
        for (Set<String> chunk : partition(tableNames, ORACLE_IN_LIST_LIMIT)) {
            String sql = """
                    SELECT c.table_name,
                           c.constraint_name,
                           cc.column_name
                    FROM user_constraints c
                    JOIN user_cons_columns cc
                      ON cc.table_name = c.table_name
                     AND cc.constraint_name = c.constraint_name
                    WHERE c.table_name IN (%s)
                      AND c.constraint_type = 'P'
                    ORDER BY c.table_name, cc.position
                    """.formatted(upperPlaceholders(chunk.size()));
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                bindTables(ps, chunk, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("table_name");
                        String name = rs.getString("constraint_name");
                        String column = rs.getString("column_name");
                        if (table == null || column == null) continue;
                        byTable.computeIfAbsent(table, ignored -> new Pending(name, new ArrayList<>()))
                                .columns().add(column);
                    }
                }
            }
        }
        Map<String, PrimaryKey> out = new LinkedHashMap<>();
        for (Map.Entry<String, Pending> entry : byTable.entrySet()) {
            out.put(entry.getKey(), new PrimaryKey(entry.getValue().name(), entry.getValue().columns()));
        }
        return out;
    }

private Map<String, List<ForeignKey>> fetchOracleForeignKeysForTables(Connection conn, String schema,
                                                                           Set<String> tableNames)
            throws SQLException {
        if (isCurrentOracleUserSchema(conn, schema)) {
            return fetchOracleUserForeignKeysForTables(conn, schema, tableNames);
        }
        record Pending(String name, String[] referencedSchema, String[] referencedTable,
                       List<String> columns, List<String> referencedColumns) {}
        Map<String, Map<String, Pending>> byTable = new LinkedHashMap<>();
        for (Set<String> chunk : partition(tableNames, ORACLE_IN_LIST_LIMIT)) {
            String sql = """
                    SELECT c.table_name,
                           c.constraint_name,
                           acc.column_name AS fk_column_name,
                           rc.owner AS referenced_schema,
                           rc.table_name AS referenced_table,
                           rcc.column_name AS referenced_column_name
                    FROM all_constraints c
                    JOIN all_cons_columns acc
                      ON acc.owner = c.owner
                     AND acc.table_name = c.table_name
                     AND acc.constraint_name = c.constraint_name
                    JOIN all_constraints rc
                      ON rc.owner = c.r_owner
                     AND rc.constraint_name = c.r_constraint_name
                    JOIN all_cons_columns rcc
                      ON rcc.owner = rc.owner
                     AND rcc.table_name = rc.table_name
                     AND rcc.constraint_name = rc.constraint_name
                     AND rcc.position = acc.position
                    WHERE c.owner = UPPER(?)
                      AND c.table_name IN (%s)
                      AND c.constraint_type = 'R'
                    ORDER BY c.table_name, c.constraint_name, acc.position
                    """.formatted(upperPlaceholders(chunk.size()));
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                bindSchemaAndTables(ps, schema, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("table_name");
                        String name = rs.getString("constraint_name");
                        String fkColumn = rs.getString("fk_column_name");
                        String refSchema = rs.getString("referenced_schema");
                        String refTable = rs.getString("referenced_table");
                        String refColumn = rs.getString("referenced_column_name");
                        if (table == null || name == null || fkColumn == null || refTable == null) continue;
                        Pending pending = byTable.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(name, ignored -> new Pending(
                                        name, new String[]{refSchema}, new String[]{refTable},
                                        new ArrayList<>(), new ArrayList<>()));
                        pending.columns().add(fkColumn);
                        if (refColumn != null) pending.referencedColumns().add(refColumn);
                    }
                }
            }
        }
        Map<String, List<ForeignKey>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Pending>> tableEntry : byTable.entrySet()) {
            List<ForeignKey> tableFks = new ArrayList<>();
            for (Pending pending : tableEntry.getValue().values()) {
                tableFks.add(new ForeignKey(pending.name(), pending.columns(),
                        pending.referencedSchema()[0] == null ? schema : pending.referencedSchema()[0],
                        pending.referencedTable()[0], pending.referencedColumns()));
            }
            out.put(tableEntry.getKey(), tableFks);
        }
        return out;
    }

private Map<String, List<ForeignKey>> fetchOracleUserForeignKeysForTables(Connection conn, String schema,
                                                                               Set<String> tableNames)
            throws SQLException {
        record Pending(String name, String[] referencedSchema, String[] referencedTable,
                       List<String> columns, List<String> referencedColumns) {}
        Map<String, Map<String, Pending>> byTable = new LinkedHashMap<>();
        for (Set<String> chunk : partition(tableNames, ORACLE_IN_LIST_LIMIT)) {
            String sql = """
                    SELECT c.table_name,
                           c.constraint_name,
                           acc.column_name AS fk_column_name,
                           rc.owner AS referenced_schema,
                           rc.table_name AS referenced_table,
                           rcc.column_name AS referenced_column_name
                    FROM user_constraints c
                    JOIN user_cons_columns acc
                      ON acc.table_name = c.table_name
                     AND acc.constraint_name = c.constraint_name
                    JOIN all_constraints rc
                      ON rc.owner = c.r_owner
                     AND rc.constraint_name = c.r_constraint_name
                    JOIN all_cons_columns rcc
                      ON rcc.owner = rc.owner
                     AND rcc.table_name = rc.table_name
                     AND rcc.constraint_name = rc.constraint_name
                     AND rcc.position = acc.position
                    WHERE c.table_name IN (%s)
                      AND c.constraint_type = 'R'
                    ORDER BY c.table_name, c.constraint_name, acc.position
                    """.formatted(upperPlaceholders(chunk.size()));
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                bindTables(ps, chunk, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString("table_name");
                        String name = rs.getString("constraint_name");
                        String fkColumn = rs.getString("fk_column_name");
                        String refSchema = rs.getString("referenced_schema");
                        String refTable = rs.getString("referenced_table");
                        String refColumn = rs.getString("referenced_column_name");
                        if (table == null || name == null || fkColumn == null || refTable == null) continue;
                        Pending pending = byTable.computeIfAbsent(table, ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(name, ignored -> new Pending(
                                        name, new String[]{refSchema}, new String[]{refTable},
                                        new ArrayList<>(), new ArrayList<>()));
                        pending.columns().add(fkColumn);
                        if (refColumn != null) pending.referencedColumns().add(refColumn);
                    }
                }
            }
        }
        Map<String, List<ForeignKey>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Pending>> tableEntry : byTable.entrySet()) {
            List<ForeignKey> tableFks = new ArrayList<>();
            for (Pending pending : tableEntry.getValue().values()) {
                tableFks.add(new ForeignKey(pending.name(), pending.columns(),
                        pending.referencedSchema()[0] == null ? schema : pending.referencedSchema()[0],
                        pending.referencedTable()[0], pending.referencedColumns()));
            }
            out.put(tableEntry.getKey(), tableFks);
        }
        return out;
    }

    private List<Constraint> fetchConstraints(Connection conn, String sql, String schema, String table)
            throws SQLException {
        List<Constraint> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setQueryTimeout(properties.queryTimeoutSeconds());
            ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
            ps.setString(1, schema == null ? "" : schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> columns = readColumns(rs);
                while (rs.next()) {
                    out.add(constraintFromRow(readRow(rs, columns)));
                }
            }
        }
        return out;
    }

    private Constraint constraintFromRow(Map<String, Object> row) {
        String name = asString(getCI(row, "name"));
        String type = asString(getCI(row, "type"));
        List<String> columns = splitCsv(getCI(row, "columns"));
        Object definitionRaw = getCI(row, "definition");
        String definition = null;
        String allowedValuesColumn = null;
        List<String> allowedValuesList = null;
        if (definitionRaw != null && !String.valueOf(definitionRaw).isBlank()) {
            definition = String.valueOf(definitionRaw);
            Map.Entry<String, List<String>> allowed = parseAllowedValues(definition);
            if (allowed != null) {
                allowedValuesColumn = allowed.getKey();
                allowedValuesList = allowed.getValue();
            }
        }
        String referencedSchema = null;
        String referencedTable = null;
        List<String> referencedColumns = null;
        Object referencedTableRaw = getCI(row, "referenced_table");
        if (referencedTableRaw != null && !String.valueOf(referencedTableRaw).isBlank()) {
            referencedSchema = asString(getCI(row, "referenced_schema"));
            referencedTable = String.valueOf(referencedTableRaw);
            referencedColumns = splitCsv(getCI(row, "referenced_columns"));
        }
        return new Constraint(name, type, columns, definition,
                allowedValuesColumn, allowedValuesList,
                referencedSchema, referencedTable, referencedColumns);
    }

    private Map<String, List<Trigger>> fetchAllTriggersBulk(Connection conn, String schema) throws SQLException {
        List<Trigger> allTriggers = fetchSchemaTriggers(conn, schema, false);
        Map<String, List<Trigger>> out = new LinkedHashMap<>();
        for (Trigger t : allTriggers) {
            out.computeIfAbsent(t.table(), k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    private Map<String, List<Trigger>> fetchTriggersForTablesBulk(
            Connection conn,
            String schema,
            Set<String> tableNames) throws SQLException {
        if (useOracleSelectedBulk(tableNames)) {
            return fetchOracleTriggersForTables(conn, schema, tableNames);
        }
        if (tableNames.size() > 100 || dialect.tableTriggersQuery() == null) {
            Map<String, List<Trigger>> all = fetchAllTriggersBulk(conn, schema);
            all.keySet().removeIf(table -> !containsTableName(tableNames, table));
            return all;
        }

        Map<String, List<Trigger>> out = new LinkedHashMap<>();
        String tableSql = dialect.tableTriggersQuery();
        for (String table : tableNames) {
            List<Trigger> triggers = fetchTriggers(conn, tableSql, schema, table, false);
            if (!triggers.isEmpty()) {
                out.put(table, triggers);
            }
        }
        return out;
    }

    private Map<String, List<Trigger>> fetchOracleTriggersForTables(
            Connection conn,
            String schema,
            Set<String> tableNames) throws SQLException {
        Map<String, List<Trigger>> out = new LinkedHashMap<>();
        for (Set<String> chunk : partition(tableNames, ORACLE_IN_LIST_LIMIT)) {
            String sql = """
                    SELECT owner AS schema,
                           table_name AS table_name,
                           trigger_name AS name,
                           CASE
                               WHEN trigger_type LIKE 'BEFORE%%' THEN 'BEFORE'
                               WHEN trigger_type LIKE 'AFTER%%' THEN 'AFTER'
                               WHEN trigger_type LIKE 'INSTEAD OF%%' THEN 'INSTEAD OF'
                               ELSE trigger_type
                           END AS timing,
                           triggering_event AS events,
                           CASE WHEN status = 'ENABLED' THEN 'true' ELSE 'false' END AS enabled,
                           description AS definition
                    FROM all_triggers
                    WHERE owner = UPPER(?)
                      AND table_name IN (%s)
                    ORDER BY table_name, trigger_name
                    """.formatted(upperPlaceholders(chunk.size()));
            try (PreparedStatement ps = conn.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                bindSchemaAndTables(ps, schema, chunk);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> columns = readColumns(rs);
                    while (rs.next()) {
                        Trigger trigger = triggerFromRow(readRow(rs, columns), false);
                        out.computeIfAbsent(trigger.table(), ignored -> new ArrayList<>()).add(trigger);
                    }
                }
            }
        }
        return out;
    }

    private List<Trigger> fetchSchemaTriggers(Connection conn, String schema, boolean includeDefinition)
            throws SQLException {
        String bulkSql = dialect.schemaTriggersQuery();
        if (bulkSql != null) {
            List<Trigger> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(bulkSql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                ps.setString(1, schema == null ? "" : schema);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> columns = readColumns(rs);
                    while (rs.next()) {
                        out.add(triggerFromRow(readRow(rs, columns), includeDefinition));
                    }
                }
            }
            return out;
        }

        String tableSql = dialect.tableTriggersQuery();
        if (tableSql == null) return List.of();
        DatabaseMetaData md = conn.getMetaData();
        List<Trigger> out = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                try (PreparedStatement ps = conn.prepareStatement(tableSql,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    ps.setQueryTimeout(properties.queryTimeoutSeconds());
                    ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                    ps.setString(1, schema == null ? "" : schema);
                    ps.setString(2, table);
                    try (ResultSet triggerRs = ps.executeQuery()) {
                        List<String> columns = readColumns(triggerRs);
                        while (triggerRs.next()) {
                            out.add(triggerFromRow(readRow(triggerRs, columns), includeDefinition));
                        }
                    }
                }
            }
        }
        return out;
    }

    private List<Trigger> fetchTriggers(Connection conn, String sql, String schema, String table,
                                        boolean includeDefinition) throws SQLException {
        List<Trigger> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setQueryTimeout(properties.queryTimeoutSeconds());
            ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
            ps.setString(1, schema == null ? "" : schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> columns = readColumns(rs);
                while (rs.next()) {
                    out.add(triggerFromRow(readRow(rs, columns), includeDefinition));
                }
            }
        }
        return out;
    }

    private Trigger triggerFromRow(Map<String, Object> row, boolean includeDefinition) {
        Object definitionRaw = getCI(row, "definition");
        String definition = (includeDefinition && definitionRaw != null
                && !String.valueOf(definitionRaw).isBlank())
                ? String.valueOf(definitionRaw) : null;
        return new Trigger(
                asString(getCI(row, "schema")),
                asString(getCI(row, "table_name")),
                asString(getCI(row, "name")),
                asString(getCI(row, "timing")),
                splitEvents(getCI(row, "events")),
                toBool(getCI(row, "enabled")),
                definition);
    }

    private String key(String schema, String table) {
        return (schema == null ? "" : schema.toLowerCase(Locale.ROOT)) + "."
                + (table == null ? "" : table.toLowerCase(Locale.ROOT));
    }

    // ---------- Views / routines / sequences / search ----------

    public String viewDefinition(String schema, String name) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        return store.viewDefinition(effectiveSchema, name,
                () -> viewDefinitionLive(schema, name));
    }

    private String viewDefinitionLive(String schema, String name) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        QueryResult r = executor.queryInternal(dialect.viewDefinitionQuery(),
                List.of(effectiveSchema == null ? "" : effectiveSchema, name), 5);
        if (r.rows().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : r.rows()) {
            Object def = row.get(r.columns().getFirst());
            if (def != null) sb.append(def);
        }
        return sb.toString();
    }

    /**
     * Bulk-load all view definitions for a schema in one query.
     * Returns a list of rows, each with {@code schema}, {@code name}, {@code definition} columns.
     * Falls back to per-view loading when the dialect does not provide a bulk query.
     */
    public List<Map<String, Object>> schemaViewDefinitions(String schema) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        return store.views(effectiveSchema, () -> schemaViewDefinitionsLive(schema));
    }

    private List<Map<String, Object>> schemaViewDefinitionsLive(String schema) throws SQLException {
        String bulkSql = dialect.schemaViewsQuery();
        if (bulkSql != null) {
            String effectiveSchema = resolveSchema(schema);
            QueryResult r = executor.queryInternal(bulkSql,
                    List.of(effectiveSchema == null ? "" : effectiveSchema), 10_000);
            return r.rows();
        }
        // fallback: per-view loading
        String effectiveSchema = resolveSchema(schema);
        List<TableEntry> viewEntries = listTables(effectiveSchema, "%",
                new String[]{"VIEW", "MATERIALIZED VIEW"});
        List<Map<String, Object>> results = new ArrayList<>();
        for (TableEntry ve : viewEntries) {
            String def = viewDefinition(ve.schema(), ve.name());
            if (def != null && !def.isBlank()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schema", ve.schema());
                row.put("name", ve.name());
                row.put("definition", def);
                results.add(row);
            }
        }
        return results;
    }

    public String routineSource(String schema, String name) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        return store.routineSource(effectiveSchema, name,
                () -> routineSourceLive(schema, name));
    }

    private String routineSourceLive(String schema, String name) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        QueryResult r = executor.queryInternal(dialect.routineSourceQuery(),
                List.of(effectiveSchema == null ? "" : effectiveSchema, name), 10_000);
        if (r.rows().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String key = r.columns().getFirst();
        for (Map<String, Object> row : r.rows()) {
            Object v = row.get(key);
            if (v != null) sb.append(v);
        }
        return sb.toString();
    }

    public List<SequenceEntry> listSequences(String schema) throws SQLException {
        return store.sequences(schema, () -> listSequencesLive(schema));
    }

    private List<SequenceEntry> listSequencesLive(String schema) throws SQLException {
        String effectiveSchema = schema == null || schema.isBlank() ? null : schema;
        QueryResult r = executor.queryInternal(dialect.listSequencesQuery(),
                Arrays.asList(effectiveSchema, effectiveSchema), 500);
        List<SequenceEntry> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            out.add(new SequenceEntry(
                    asString(getCI(row, "schema")),
                    asString(getCI(row, "name"))));
        }
        return out;
    }

    public List<RoutineEntry> listRoutines(String schema, String namePattern) throws SQLException {
        return store.routines(schema, namePattern, () -> listRoutinesLive(schema, namePattern));
    }

    private List<RoutineEntry> listRoutinesLive(String schema, String namePattern) throws SQLException {
        String s = schema == null || schema.isBlank() ? null : schema;
        String p = namePattern == null || namePattern.isBlank() ? null : namePattern;
        QueryResult r = executor.queryInternal(dialect.listRoutinesQuery(),
                Arrays.asList(s, s, p, p), 500);
        List<RoutineEntry> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            out.add(new RoutineEntry(
                    asString(getCI(row, "schema")),
                    asString(getCI(row, "name")),
                    asString(getCI(row, "type"))));
        }
        return out;
    }

    public List<SearchObjectEntry> searchObjects(String namePattern) throws SQLException {
        return store.searchObjects(namePattern, () -> searchObjectsLive(namePattern));
    }

    private List<SearchObjectEntry> searchObjectsLive(String namePattern) throws SQLException {
        String pattern = (namePattern == null || namePattern.isBlank())
                ? "%" : namePattern.contains("%") ? namePattern : "%" + namePattern + "%";
        QueryResult r = executor.queryInternal(dialect.searchObjectsQuery(),
                Arrays.asList(pattern, pattern), 200);
        List<SearchObjectEntry> out = new ArrayList<>(r.rows().size());
        for (Map<String, Object> row : r.rows()) {
            out.add(new SearchObjectEntry(
                    asString(getCI(row, "schema")),
                    asString(getCI(row, "name")),
                    asString(getCI(row, "type"))));
        }
        return out;
    }

    public String triggerDefinition(String schema, String table, String trigger) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must be provided");
        }
        if (trigger == null || trigger.isBlank()) {
            throw new IllegalArgumentException("trigger must be provided");
        }
        String effectiveSchema = resolveSchema(schema);
        return store.triggerDefinition(effectiveSchema, table, trigger,
                () -> triggerDefinitionLive(schema, table, trigger));
    }

    private String triggerDefinitionLive(String schema, String table, String trigger) throws SQLException {
        String sql = dialect.triggerDefinitionQuery();
        if (sql == null) return null;
        QueryResult r = executor.queryInternal(sql, List.of(resolveSchema(schema), table, trigger), 10_000);
        if (r.rows().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String key = r.columns().getFirst();
        for (Map<String, Object> row : r.rows()) {
            Object value = row.get(key);
            if (value != null) sb.append(value);
        }
        return sb.toString();
    }

    /**
     * Bulk-load all triggers for a schema in one query.
     * Falls back to per-table loading when the dialect does not provide a bulk query.
     */
    public List<Trigger> schemaTriggers(String schema, boolean includeDefinition) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        return store.triggers(effectiveSchema, includeDefinition,
                () -> schemaTriggersLive(schema, includeDefinition));
    }

    private List<Trigger> schemaTriggersLive(String schema, boolean includeDefinition) throws SQLException {
        String bulkSql = dialect.schemaTriggersQuery();
        if (bulkSql != null) {
            String effectiveSchema = resolveSchema(schema);
            QueryResult r = executor.queryInternal(bulkSql,
                    List.of(effectiveSchema == null ? "" : effectiveSchema), 10_000);
            List<Trigger> out = new ArrayList<>();
            for (Map<String, Object> row : r.rows()) {
                Object definitionRaw = getCI(row, "definition");
                String definition = (includeDefinition && definitionRaw != null
                        && !String.valueOf(definitionRaw).isBlank())
                        ? String.valueOf(definitionRaw) : null;
                out.add(new Trigger(
                        asString(getCI(row, "schema")),
                        asString(getCI(row, "table_name")),
                        asString(getCI(row, "name")),
                        asString(getCI(row, "timing")),
                        splitEvents(getCI(row, "events")),
                        toBool(getCI(row, "enabled")),
                        definition));
            }
            return out;
        }
        // fallback: per-table loading
        String effectiveSchema = resolveSchema(schema);
        List<TableEntry> tables = listTables(effectiveSchema, "%", new String[]{"TABLE"});
        List<Trigger> out = new ArrayList<>();
        for (TableEntry table : tables) {
            out.addAll(fetchTriggers(table.schema(), table.name(), includeDefinition));
        }
        return out;
    }

    // ---------- structure snapshot build-all ----------

    /**
     * Front-load the whole configured scope into the persistent structure snapshot in one
     * transaction. Scope = {@code structure-snapshot.schemas}, or the resolved default schema when
     * empty (same fallback as {@code DatabaseNativeUsageSourceProvider.schemas()}).
     *
     * @return the schemas actually covered by the rebuild
     */
    public List<String> rebuildStructureSnapshot() throws SQLException {
        List<String> scope = snapshotProperties.resolvedSchemas();
        if (scope.isEmpty()) {
            String def = defaultSchema();
            scope = (def == null || def.isBlank()) ? List.of() : List.of(def);
        }
        return rebuildStructureSnapshot(scope);
    }

    /**
     * Build-all over an explicit list of schemas. Loads tables (full descriptions → JSON +
     * projection), view definitions, routines with source, triggers with body and sequences from
     * the live database, then replaces the whole structure snapshot atomically.
     *
     * @return the schemas actually covered by the rebuild
     */
    public List<String> rebuildStructureSnapshot(List<String> schemas) throws SQLException {
        List<String> scope = schemas == null ? List.of() : schemas;
        List<String> covered = new ArrayList<>();
        List<TableDescription> tables = new ArrayList<>();
        List<StructureSnapshotStore.ViewRecord> views = new ArrayList<>();
        List<StructureSnapshotStore.RoutineRecord> routines = new ArrayList<>();
        List<Trigger> triggers = new ArrayList<>();
        List<SequenceEntry> sequences = new ArrayList<>();

        for (String rawSchema : scope) {
            String effectiveSchema = resolveSchema(rawSchema);
            covered.add(effectiveSchema == null ? "" : effectiveSchema);

            List<TableEntry> tableEntries = listTablesUncached(effectiveSchema, "%",
                    new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"});
            Set<String> names = new LinkedHashSet<>();
            for (TableEntry te : tableEntries) {
                if (te.name() != null) names.add(te.name());
            }
            if (!names.isEmpty()) {
                tables.addAll(describeListedTables(effectiveSchema, names).values());
            }

            for (Map<String, Object> row : schemaViewDefinitionsLive(rawSchema)) {
                String viewSchema = asString(getCI(row, "schema"));
                String viewName = asString(getCI(row, "name"));
                String definition = asString(getCI(row, "definition"));
                if (viewName != null) {
                    views.add(new StructureSnapshotStore.ViewRecord(
                            viewSchema != null ? viewSchema : effectiveSchema, viewName, definition));
                }
            }

            for (RoutineEntry routine : listRoutinesLive(rawSchema, "%")) {
                if (routine.name() == null) continue;
                String source = routineSourceLive(rawSchema, routine.name());
                routines.add(new StructureSnapshotStore.RoutineRecord(
                        routine.schema() != null ? routine.schema() : effectiveSchema,
                        routine.name(), routine.type(), source));
            }

            triggers.addAll(schemaTriggersLive(rawSchema, true));
            sequences.addAll(listSequencesLive(rawSchema));
        }

        store.rebuild(new StructureSnapshotStore.StructureSnapshotData(
                covered, tables, views, routines, triggers, sequences));
        return List.copyOf(covered);
    }

    // ---------- helpers ----------

    private List<Constraint> fetchConstraints(String schema, String table) throws SQLException {
        String sql = dialect.tableConstraintsQuery();
        if (sql == null) return List.of();
        QueryResult r = executor.queryInternal(sql, List.of(schema == null ? "" : schema, table), 1_000);
        List<Constraint> out = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            String name = asString(getCI(row, "name"));
            String type = asString(getCI(row, "type"));
            List<String> columns = splitCsv(getCI(row, "columns"));
            Object definitionRaw = getCI(row, "definition");
            String definition = null;
            String allowedValuesColumn = null;
            List<String> allowedValuesList = null;
            if (definitionRaw != null && !String.valueOf(definitionRaw).isBlank()) {
                definition = String.valueOf(definitionRaw);
                Map.Entry<String, List<String>> allowed = parseAllowedValues(definition);
                if (allowed != null) {
                    allowedValuesColumn = allowed.getKey();
                    allowedValuesList = allowed.getValue();
                }
            }
            String referencedSchema = null;
            String referencedTable = null;
            List<String> referencedColumns = null;
            Object referencedTableRaw = getCI(row, "referenced_table");
            if (referencedTableRaw != null && !String.valueOf(referencedTableRaw).isBlank()) {
                referencedSchema = asString(getCI(row, "referenced_schema"));
                referencedTable = String.valueOf(referencedTableRaw);
                referencedColumns = splitCsv(getCI(row, "referenced_columns"));
            }
            out.add(new Constraint(name, type, columns, definition,
                    allowedValuesColumn, allowedValuesList,
                    referencedSchema, referencedTable, referencedColumns));
        }
        return out;
    }

    private Map<String, List<String>> extractAllowedValues(List<Constraint> constraints) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Constraint constraint : constraints) {
            String column = constraint.allowedValuesColumn();
            List<String> values = constraint.allowedValues();
            if (column != null && values != null && !values.isEmpty()) {
                out.put(column, values);
            }
        }
        return out;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map.Entry<String, List<String>> parseAllowedValues(String definition) {
        try {
            Map.Entry<String, List<String>> pgArray = parsePostgresArrayAllowedValues(definition);
            if (pgArray != null) return pgArray;
            return parseInAllowedValues(definition);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Map.Entry<String, List<String>> parseInAllowedValues(String definition) {
        String upper = definition.toUpperCase();
        int inPos = upper.indexOf(" IN ");
        if (inPos < 0) return null;
        int open = definition.indexOf('(', inPos);
        int close = definition.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) return null;
        String before = definition.substring(0, inPos)
                .replace("CHECK", "").replace("(", "").replace("\"", "").trim();
        String column = before.contains(" ") ? before.substring(before.lastIndexOf(' ') + 1) : before;
        List<String> values = parseLiteralList(definition.substring(open + 1, close));
        if (column.isBlank() || values.size() < 2 || values.size() > 100) return null;
        return Map.entry(column, values);
    }

    private Map.Entry<String, List<String>> parsePostgresArrayAllowedValues(String definition) {
        String upper = definition.toUpperCase();
        int anyPos = upper.indexOf("= ANY");
        int arrayPos = upper.indexOf("ARRAY[", anyPos);
        if (anyPos < 0 || arrayPos < 0) return null;
        String before = definition.substring(0, anyPos)
                .replace("CHECK", "").replace("(", "").replace("\"", "").trim();
        String column = before.contains(" ") ? before.substring(before.lastIndexOf(' ') + 1) : before;
        int open = definition.indexOf('[', arrayPos);
        int close = definition.indexOf(']', open + 1);
        if (open < 0 || close < 0 || close <= open) return null;
        List<String> values = parseLiteralList(definition.substring(open + 1, close));
        if (column.isBlank() || values.size() < 2 || values.size() > 100) return null;
        return Map.entry(column, values);
    }

    private List<String> parseLiteralList(String raw) {
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            int cast = value.indexOf("::");
            if (cast >= 0) value = value.substring(0, cast);
            value = value.replace("'", "").replace("\"", "").trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private List<Trigger> fetchTriggers(String schema, String table, boolean includeDefinition)
            throws SQLException {
        String sql = dialect.tableTriggersQuery();
        if (sql == null) return List.of();
        QueryResult r = executor.queryInternal(sql, List.of(schema == null ? "" : schema, table), 1_000);
        List<Trigger> out = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            Object definitionRaw = getCI(row, "definition");
            String definition = (includeDefinition && definitionRaw != null
                    && !String.valueOf(definitionRaw).isBlank())
                    ? String.valueOf(definitionRaw) : null;
            out.add(new Trigger(
                    asString(getCI(row, "schema")),
                    asString(getCI(row, "table_name")),
                    asString(getCI(row, "name")),
                    asString(getCI(row, "timing")),
                    splitEvents(getCI(row, "events")),
                    toBool(getCI(row, "enabled")),
                    definition));
        }
        return out;
    }

    private static Object getCI(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static List<String> splitCsv(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : String.valueOf(value).split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    private static List<String> splitEvents(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        String normalized = String.valueOf(value).replace(" OR ", ",");
        return splitCsv(normalized);
    }

    private static boolean toBool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                || "y".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String resolveSchema(String schema) throws SQLException {
        return schemaResolver.resolve(schema);
    }

    private boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        var set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(dialect.systemSchemas());
        return set.contains(schema);
    }
}
