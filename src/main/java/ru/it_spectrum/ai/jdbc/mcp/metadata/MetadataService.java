package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
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

    public MetadataService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
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
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", effectiveSchema);
            result.put("name", table);
            result.put("type", fetchTableType(md, effectiveSchema, table));
            result.put("remarks", fetchTableRemarks(md, effectiveSchema, table));
            List<Map<String, Object>> cols = fetchColumns(md, effectiveSchema, table);
            // Supplement COLUMN_DEF and REMARKS via dialect-specific queries (bypasses LONG restriction
            // on Oracle's DatabaseMetaData.getColumns / getString).
            fetchColumnMetadataSupplement(cols, effectiveSchema, table);
            result.put("columns", cols);
            result.put("primaryKey", fetchPrimaryKey(md, effectiveSchema, table));
            result.put("uniqueConstraints", fetchUniqueConstraints(md, effectiveSchema, table));
            result.put("indexes", fetchIndexes(md, effectiveSchema, table));
            result.put("foreignKeys", fetchImportedKeys(md, effectiveSchema, table));
            result.put("referencedBy", fetchExportedKeys(md, effectiveSchema, table));
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

    private void fetchColumnMetadataSupplement(List<Map<String, Object>> cols, String schema, String table)
            throws SQLException {
        String commentsSql = dialect.columnCommentsQuery();
        String defaultsSql = dialect.columnDefaultsQuery();

        Map<String, String> comments = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();

        if (commentsSql != null) {
            try {
                QueryResult r = executor.queryInternal(commentsSql, List.of(schema, table), 1000);
                List<String> colsList = r.columns();
                if (colsList.size() >= 2) {
                    String nameCol = colsList.get(0);
                    String valCol = colsList.get(1);
                    for (Map<String, Object> row : r.rows()) {
                        Object nameVal = row.get(nameCol);
                        Object commentVal = row.get(valCol);
                        if (nameVal != null && commentVal != null && !String.valueOf(commentVal).isBlank()) {
                            comments.put(String.valueOf(nameVal).toUpperCase(), String.valueOf(commentVal));
                        }
                    }
                }
            } catch (SQLException e) {
                // silently skip — remarks will remain null for this column
            }
        }

        if (defaultsSql != null) {
            try {
                QueryResult r = executor.queryInternal(defaultsSql, List.of(schema, table), 1000);
                List<String> colsList = r.columns();
                if (colsList.size() >= 2) {
                    String nameCol = colsList.get(0);
                    String valCol = colsList.get(1);
                    for (Map<String, Object> row : r.rows()) {
                        Object nameVal = row.get(nameCol);
                        Object defVal = row.get(valCol);
                        if (nameVal != null && defVal != null && !String.valueOf(defVal).isBlank()) {
                            defaults.put(String.valueOf(nameVal).toUpperCase(), String.valueOf(defVal));
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

    // ---------- helpers ----------

    private String resolveSchema(String schema) throws SQLException {
        if (schema != null && !schema.isBlank()) return schema;
        if (properties.defaultSchema() != null && !properties.defaultSchema().isBlank()) {
            return properties.defaultSchema();
        }
        return executor.withConnection(dialect::fallbackSchema);
    }

    private boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        var set = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(dialect.systemSchemas());
        return set.contains(schema);
    }
}
