package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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

    public SchemaSnapshotCache cache() {
        return cache;
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

    public List<Map<String, Object>> listTables(String schema, String namePattern, String[] types)
            throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        String[] effectiveTypes = types != null && types.length > 0
                ? types
                : new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"};
        return cache.listTables(effectiveSchema, namePattern, effectiveTypes,
                () -> listTablesUncached(effectiveSchema, namePattern, effectiveTypes));
    }

    private List<Map<String, Object>> listTablesUncached(String effectiveSchema, String namePattern,
                                                         String[] effectiveTypes) throws SQLException {
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, effectiveSchema,
                    namePattern == null || namePattern.isBlank() ? "%" : namePattern,
                    effectiveTypes)) {
                while (rs.next()) {
                    String s = rs.getString("TABLE_SCHEM");
                    if (effectiveSchema == null && isSystemSchema(s)) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("schema", s);
                    row.put("name", rs.getString("TABLE_NAME"));
                    row.put("type", rs.getString("TABLE_TYPE"));
                    row.put("remarks", rs.getString("REMARKS"));
                    out.add(row);
                }
            }
            return out;
        });
    }

    // ---------- describeTable (columns + PK + indexes + FKs) ----------

    public Map<String, Object> describeTable(String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must be provided");
        }
        String effectiveSchema = resolveSchema(schema);
        return cache.describeTable(effectiveSchema, table,
                () -> describeTableUncached(effectiveSchema, table));
    }

    private Map<String, Object> describeTableUncached(String effectiveSchema, String table) throws SQLException {
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", effectiveSchema);
            result.put("name", table);
            result.put("type", fetchTableType(md, effectiveSchema, table));
            result.put("remarks", fetchTableRemarks(md, effectiveSchema, table));
            List<Map<String, Object>> cols = fetchColumns(md, effectiveSchema, table);
            // Supplement COLUMN_DEF and REMARKS via dialect-specific queries (bypasses LONG restriction
            // on Oracle's DatabaseMetaData.getColumns / getString). Uses the same connection to avoid
            // consuming extra pooled connections.
            fetchColumnMetadataSupplement(conn, cols, effectiveSchema, table);
            result.put("columns", cols);
            result.put("primaryKey", fetchPrimaryKey(md, effectiveSchema, table));
            result.put("uniqueConstraints", fetchUniqueConstraints(md, effectiveSchema, table));
            result.put("indexes", fetchIndexes(md, effectiveSchema, table));
            result.put("foreignKeys", fetchImportedKeys(md, effectiveSchema, table));
            result.put("referencedBy", fetchExportedKeys(md, effectiveSchema, table));
            List<Map<String, Object>> constraints = fetchConstraints(effectiveSchema, table);
            result.put("constraints", constraints);
            result.put("allowedValues", extractAllowedValues(constraints));
            result.put("triggers", fetchTriggers(effectiveSchema, table, false));
            return result;
        });
    }

    private String fetchTableType(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getTables(null, schema, table, null)) {
            if (rs.next()) return rs.getString("TABLE_TYPE");
        }
        return null;
    }

    private String fetchTableRemarks(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getTables(null, schema, table, null)) {
            if (rs.next()) return rs.getString("REMARKS");
        }
        return null;
    }

    private List<Map<String, Object>> fetchColumns(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        List<Map<String, Object>> cols = new ArrayList<>();
        try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", rs.getString("COLUMN_NAME"));
                col.put("ordinalPosition", rs.getInt("ORDINAL_POSITION"));
                col.put("typeName", rs.getString("TYPE_NAME"));
                col.put("size", rs.getInt("COLUMN_SIZE"));
                int decimals = rs.getInt("DECIMAL_DIGITS");
                if (!rs.wasNull()) col.put("decimalDigits", decimals);
                col.put("nullable", "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                // NOTE: COLUMN_DEF and REMARKS are LONG in Oracle — fetched separately via
                // dialect-specific queries (columnDefaultsQuery / columnCommentsQuery) to avoid
                // ORA-17027. These fields will be added by fetchColumnMetadataSupplement.
                col.put("default", null);
                col.put("remarks", null);
                String autoInc = null;
                try {
                    autoInc = rs.getString("IS_AUTOINCREMENT");
                } catch (SQLException ignore) {
                    // some drivers may not provide this column
                }
                if ("YES".equalsIgnoreCase(autoInc)) col.put("autoIncrement", true);
                cols.add(col);
            }
        }
        cols.sort((a, b) -> Integer.compare(
                (Integer) a.getOrDefault("ordinalPosition", 0),
                (Integer) b.getOrDefault("ordinalPosition", 0)));
        return cols;
    }

    private void fetchColumnMetadataSupplement(Connection conn, List<Map<String, Object>> cols, String schema, String table)
            throws SQLException {
        String commentsSql = dialect.columnCommentsQuery();
        String defaultsSql = dialect.columnDefaultsQuery();

        Map<String, String> comments = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();

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

        for (Map<String, Object> col : cols) {
            String cn = String.valueOf(col.get("name")).toUpperCase();
            String comment = comments.get(cn);
            if (comment != null && !comment.isBlank()) col.put("remarks", comment);
            String def = defaults.get(cn);
            if (def != null && !def.isBlank()) col.put("default", def);
        }
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

    private Map<String, Object> fetchPrimaryKey(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        List<String> cols = new ArrayList<>();
        String name = null;
        try (ResultSet rs = md.getPrimaryKeys(null, schema, table)) {
            // ORDER BY KEY_SEQ to preserve column order
            List<Object[]> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Object[]{rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"), rs.getString("PK_NAME")});
            }
            rows.sort((a, b) -> Short.compare((Short) a[0], (Short) b[0]));
            for (Object[] r : rows) {
                cols.add((String) r[1]);
                if (name == null) name = (String) r[2];
            }
        }
        if (cols.isEmpty()) return null;
        Map<String, Object> pk = new LinkedHashMap<>();
        pk.put("name", name);
        pk.put("columns", cols);
        return pk;
    }

    private List<Map<String, Object>> fetchUniqueConstraints(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, /*unique*/ true, /*approximate*/ false)) {
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idxName == null || col == null) continue;
                Map<String, Object> info = byName.computeIfAbsent(idxName, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k);
                    m.put("columns", new ArrayList<String>());
                    return m;
                });
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) info.get("columns");
                cols.add(col);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<Map<String, Object>> fetchIndexes(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, false, false)) {
            while (rs.next()) {
                String idxName = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idxName == null) continue;
                Map<String, Object> info = byName.computeIfAbsent(idxName, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k);
                    m.put("unique", !rs_unchecked(rs, "NON_UNIQUE", true));
                    m.put("columns", new ArrayList<String>());
                    return m;
                });
                if (col != null) {
                    @SuppressWarnings("unchecked")
                    List<String> cols = (List<String>) info.get("columns");
                    cols.add(col);
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    private boolean rs_unchecked(ResultSet rs, String col, boolean fallback) {
        try {
            return rs.getBoolean(col);
        } catch (SQLException e) {
            return fallback;
        }
    }

    private List<Map<String, Object>> fetchImportedKeys(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getImportedKeys(null, schema, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                if (fkName == null) fkName = "fk_anon_" + rs.getString("FKCOLUMN_NAME");
                Map<String, Object> fk = byName.computeIfAbsent(fkName, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k);
                    m.put("columns", new ArrayList<String>());
                    m.put("referencedSchema", null);
                    m.put("referencedTable", null);
                    m.put("referencedColumns", new ArrayList<String>());
                    return m;
                });
                fk.put("referencedSchema", rs.getString("PKTABLE_SCHEM"));
                fk.put("referencedTable", rs.getString("PKTABLE_NAME"));
                @SuppressWarnings("unchecked")
                List<String> cols = (List<String>) fk.get("columns");
                cols.add(rs.getString("FKCOLUMN_NAME"));
                @SuppressWarnings("unchecked")
                List<String> refCols = (List<String>) fk.get("referencedColumns");
                refCols.add(rs.getString("PKCOLUMN_NAME"));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private List<Map<String, Object>> fetchExportedKeys(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getExportedKeys(null, schema, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                if (fkName == null) fkName = "fk_anon_" + rs.getString("FKCOLUMN_NAME");
                Map<String, Object> fk = byName.computeIfAbsent(fkName, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", k);
                    m.put("fromSchema", null);
                    m.put("fromTable", null);
                    m.put("fromColumns", new ArrayList<String>());
                    m.put("toColumns", new ArrayList<String>());
                    return m;
                });
                fk.put("fromSchema", rs.getString("FKTABLE_SCHEM"));
                fk.put("fromTable", rs.getString("FKTABLE_NAME"));
                @SuppressWarnings("unchecked")
                List<String> fromCols = (List<String>) fk.get("fromColumns");
                fromCols.add(rs.getString("FKCOLUMN_NAME"));
                @SuppressWarnings("unchecked")
                List<String> toCols = (List<String>) fk.get("toColumns");
                toCols.add(rs.getString("PKCOLUMN_NAME"));
            }
        }
        return new ArrayList<>(byName.values());
    }

    // ---------- Views / routines / sequences / search ----------

    public String viewDefinition(String schema, String name) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        QueryResult r = executor.queryInternal(dialect.viewDefinitionQuery(),
                List.of(effectiveSchema == null ? "" : effectiveSchema, name), 5);
        if (r.rows().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : r.rows()) {
            Object def = row.get(r.columns().get(0));
            if (def != null) sb.append(def);
        }
        return sb.toString();
    }

    public String routineSource(String schema, String name) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        QueryResult r = executor.queryInternal(dialect.routineSourceQuery(),
                List.of(effectiveSchema == null ? "" : effectiveSchema, name), 10_000);
        if (r.rows().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String key = r.columns().get(0);
        for (Map<String, Object> row : r.rows()) {
            Object v = row.get(key);
            if (v != null) sb.append(v);
        }
        return sb.toString();
    }

    public QueryResult listSequences(String schema) throws SQLException {
        String effectiveSchema = schema == null || schema.isBlank() ? null : schema;
        return executor.queryInternal(dialect.listSequencesQuery(),
                Arrays.asList(effectiveSchema, effectiveSchema), 500);
    }

    public QueryResult listRoutines(String schema, String namePattern) throws SQLException {
        String s = schema == null || schema.isBlank() ? null : schema;
        String p = namePattern == null || namePattern.isBlank() ? null : namePattern;
        return executor.queryInternal(dialect.listRoutinesQuery(),
                Arrays.asList(s, s, p, p), 500);
    }

    public QueryResult searchObjects(String namePattern) throws SQLException {
        String pattern = (namePattern == null || namePattern.isBlank())
                ? "%" : namePattern.contains("%") ? namePattern : "%" + namePattern + "%";
        return executor.queryInternal(dialect.searchObjectsQuery(),
                Arrays.asList(pattern, pattern), 200);
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
        String key = r.columns().get(0);
        for (Map<String, Object> row : r.rows()) {
            Object value = row.get(key);
            if (value != null) sb.append(value);
        }
        return sb.toString();
    }

    // ---------- helpers ----------

    private List<Map<String, Object>> fetchConstraints(String schema, String table) throws SQLException {
        String sql = dialect.tableConstraintsQuery();
        if (sql == null) return List.of();
        QueryResult r = executor.queryInternal(sql, List.of(schema == null ? "" : schema, table), 1_000);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> constraint = new LinkedHashMap<>();
            constraint.put("name", getCI(row, "name"));
            constraint.put("type", getCI(row, "type"));
            constraint.put("columns", splitCsv(getCI(row, "columns")));
            Object definition = getCI(row, "definition");
            if (definition != null && !String.valueOf(definition).isBlank()) {
                constraint.put("definition", definition);
                Map.Entry<String, List<String>> allowed = parseAllowedValues(String.valueOf(definition));
                if (allowed != null) {
                    constraint.put("allowedValuesColumn", allowed.getKey());
                    constraint.put("allowedValues", allowed.getValue());
                }
            }
            Object referencedSchema = getCI(row, "referenced_schema");
            Object referencedTable = getCI(row, "referenced_table");
            List<String> referencedColumns = splitCsv(getCI(row, "referenced_columns"));
            if (referencedTable != null && !String.valueOf(referencedTable).isBlank()) {
                constraint.put("referencedSchema", referencedSchema);
                constraint.put("referencedTable", referencedTable);
                constraint.put("referencedColumns", referencedColumns);
            }
            out.add(constraint);
        }
        return out;
    }

    private Map<String, List<String>> extractAllowedValues(List<Map<String, Object>> constraints) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map<String, Object> constraint : constraints) {
            Object column = constraint.get("allowedValuesColumn");
            Object values = constraint.get("allowedValues");
            if (column instanceof String c && values instanceof List<?> list && !list.isEmpty()) {
                List<String> cleaned = new ArrayList<>();
                for (Object value : list) {
                    if (value != null) cleaned.add(String.valueOf(value));
                }
                if (!cleaned.isEmpty()) out.put(c, cleaned);
            }
        }
        return out;
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

    private List<Map<String, Object>> fetchTriggers(String schema, String table, boolean includeDefinition)
            throws SQLException {
        String sql = dialect.tableTriggersQuery();
        if (sql == null) return List.of();
        QueryResult r = executor.queryInternal(sql, List.of(schema == null ? "" : schema, table), 1_000);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : r.rows()) {
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("schema", getCI(row, "schema"));
            trigger.put("table", getCI(row, "table_name"));
            trigger.put("name", getCI(row, "name"));
            trigger.put("timing", getCI(row, "timing"));
            trigger.put("events", splitEvents(getCI(row, "events")));
            trigger.put("enabled", toBool(getCI(row, "enabled")));
            Object definition = getCI(row, "definition");
            if (includeDefinition && definition != null && !String.valueOf(definition).isBlank()) {
                trigger.put("definition", definition);
            }
            out.add(trigger);
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
        var set = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(dialect.systemSchemas());
        return set.contains(schema);
    }
}
