package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
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
    private final SchemaSnapshotCache cache;
    private final SchemaResolver schemaResolver;

    public MetadataService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties) {
        this(executor, dialect, properties, new SchemaSnapshotCache(properties),
                new SchemaResolver(properties, executor, dialect));
    }

    public MetadataService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties,
                           SchemaSnapshotCache cache) {
        this(executor, dialect, properties, cache,
                new SchemaResolver(properties, executor, dialect));
    }

    @Autowired
    public MetadataService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties,
                           SchemaSnapshotCache cache, SchemaResolver schemaResolver) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.cache = cache;
        this.schemaResolver = schemaResolver;
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
        return cache.listTables(effectiveSchema, namePattern, effectiveTypes,
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
        return cache.describeTable(effectiveSchema, table,
                () -> describeTableUncached(effectiveSchema, table));
    }

    private TableDescription describeTableUncached(String effectiveSchema, String table) throws SQLException {
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            TableInfo info = fetchTableInfo(md, effectiveSchema, table);
            List<Column> cols = fetchColumns(md, effectiveSchema, table);
            // Supplement COLUMN_DEF and REMARKS via dialect-specific queries (bypasses LONG restriction
            // on Oracle's DatabaseMetaData.getColumns / getString). Uses the same connection to avoid
            // consuming extra pooled connections.
            cols = fetchColumnMetadataSupplement(conn, cols, effectiveSchema, table);
            PrimaryKey pk = fetchPrimaryKey(md, effectiveSchema, table);
            List<UniqueConstraint> uniqueConstraints = fetchUniqueConstraints(md, effectiveSchema, table);
            List<Index> indexes = fetchIndexes(md, effectiveSchema, table);
            List<ForeignKey> foreignKeys = fetchImportedKeys(conn, md, effectiveSchema, table);
            List<IncomingForeignKey> referencedBy = fetchExportedKeys(conn, md, effectiveSchema, table);
            List<Constraint> constraints = fetchConstraints(effectiveSchema, table);
            Map<String, List<String>> allowedValues = extractAllowedValues(constraints);
            List<Trigger> triggers = fetchTriggers(effectiveSchema, table, false);
            return new TableDescription(
                    effectiveSchema, table, info.type, info.remarks,
                    cols, pk, uniqueConstraints, indexes,
                    foreignKeys, referencedBy, constraints,
                    allowedValues, triggers);
        });
    }

    private record TableInfo(String type, String remarks) {}

    private TableInfo fetchTableInfo(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getTables(null, schema, table, null)) {
            if (rs.next()) return new TableInfo(rs.getString("TABLE_TYPE"), rs.getString("REMARKS"));
        }
        return new TableInfo(null, null);
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
                // NOTE: COLUMN_DEF and REMARKS are LONG in Oracle — fetched separately via
                // dialect-specific queries (columnDefaultsQuery / columnCommentsQuery) to avoid
                // ORA-17027. fetchColumnMetadataSupplement will fill default/remarks.
                cols.add(new Column(name, ordinal, typeName, size, decimalDigits, nullable,
                        null, null, autoIncrement));
            }
        }
        cols.sort(Comparator.comparingInt(Column::ordinalPosition));
        return cols;
    }

    private List<Column> fetchColumnMetadataSupplement(Connection conn, List<Column> cols,
                                                       String schema, String table) throws SQLException {
        Map<String, String> comments = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();

        // Try combined query first (fewer roundtrips)
        String combinedSql = dialect.columnMetadataQuery();
        if (combinedSql != null) {
            try (PreparedStatement ps = conn.prepareStatement(combinedSql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> colsList = readColumns(rs);
                    if (colsList.size() >= 3) {
                        String nameCol = colsList.get(0);
                        String commentCol = colsList.get(1);
                        String defaultCol = colsList.get(2);
                        while (rs.next()) {
                            Object nameVal = rs.getObject(nameCol);
                            if (nameVal == null) continue;
                            String cn = String.valueOf(nameVal).toUpperCase();
                            Object commentVal = rs.getObject(commentCol);
                            if (commentVal != null && !String.valueOf(commentVal).isBlank()) {
                                comments.put(cn, String.valueOf(commentVal));
                            }
                            Object defVal = rs.getObject(defaultCol);
                            if (defVal != null && !String.valueOf(defVal).isBlank()) {
                                defaults.put(cn, String.valueOf(defVal));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                // fall through to individual queries
            }
            if (!comments.isEmpty() || !defaults.isEmpty()) {
                return mergeColumnMetadata(cols, comments, defaults);
            }
        }

        String commentsSql = dialect.columnCommentsQuery();
        String defaultsSql = dialect.columnDefaultsQuery();

        if (commentsSql != null) {
            try (PreparedStatement ps = conn.prepareStatement(commentsSql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> colsList = readColumns(rs);
                    if (colsList.size() >= 2) {
                        String nameCol = colsList.get(0);
                        String valCol = colsList.get(1);
                        while (rs.next()) {
                            Object nameVal = rs.getObject(nameCol);
                            Object commentVal = rs.getObject(valCol);
                            if (nameVal != null && commentVal != null && !String.valueOf(commentVal).isBlank()) {
                                comments.put(String.valueOf(nameVal).toUpperCase(), String.valueOf(commentVal));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                // silently skip — remarks will remain null for this column
            }
        }

        if (defaultsSql != null) {
            try (PreparedStatement ps = conn.prepareStatement(defaultsSql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setQueryTimeout(properties.queryTimeoutSeconds());
                ps.setFetchSize(properties.fetchSize() > 0 ? properties.fetchSize() : 100);
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> colsList = readColumns(rs);
                    if (colsList.size() >= 2) {
                        String nameCol = colsList.get(0);
                        String valCol = colsList.get(1);
                        while (rs.next()) {
                            Object nameVal = rs.getObject(nameCol);
                            Object defVal = rs.getObject(valCol);
                            if (nameVal != null && defVal != null && !String.valueOf(defVal).isBlank()) {
                                defaults.put(String.valueOf(nameVal).toUpperCase(), String.valueOf(defVal));
                            }
                        }
                    }
                }
            } catch (SQLException e) {
                // silently skip — defaults will remain null for this column
            }
        }

        return mergeColumnMetadata(cols, comments, defaults);
    }

    private List<Column> mergeColumnMetadata(List<Column> cols,
                                              Map<String, String> comments,
                                              Map<String, String> defaults) {
        List<Column> out = new ArrayList<>(cols.size());
        for (Column col : cols) {
            String cn = col.name() == null ? "" : col.name().toUpperCase();
            String comment = comments.get(cn);
            String def = defaults.get(cn);
            String newDefault = (def != null && !def.isBlank()) ? def : col.defaultValue();
            String newRemarks = (comment != null && !comment.isBlank()) ? comment : col.remarks();
            out.add(new Column(col.name(), col.ordinalPosition(), col.typeName(), col.size(),
                    col.decimalDigits(), col.nullable(), newDefault, newRemarks, col.autoIncrement()));
        }
        return out;
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

    private List<UniqueConstraint> fetchUniqueConstraints(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, /*unique*/ true, /*approximate*/ false)) {
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idxName == null || col == null) continue;
                byName.computeIfAbsent(idxName, k -> new ArrayList<>()).add(col);
            }
        }
        List<UniqueConstraint> out = new ArrayList<>(byName.size());
        byName.forEach((name, cols) -> out.add(new UniqueConstraint(name, cols)));
        return out;
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

    // ---------- Views / routines / sequences / search ----------

    public String viewDefinition(String schema, String name) throws SQLException {
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
