package ru.it_spectrum.ai.jdbc.mcp.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SearchObjectEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SequenceEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * H2-backed {@link StructureSnapshotStore}. Persists structure into the shared {@code <catalog>.db}
 * file alongside the usage catalog. See {@link StructureSnapshotStore} for the cache-forever
 * semantics and scope rules.
 */
@Service
public class H2StructureSnapshotStore implements StructureSnapshotStore {

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public H2StructureSnapshotStore(@Qualifier("usageDataSource") DataSource dataSource,
                                    ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    // ---------- tables ----------

    @Override
    public List<TableEntry> listTables(String schema, String namePattern, String[] types,
                                       SqlSupplier<List<TableEntry>> loader) throws SQLException {
        String s = norm(schema);
        if (!isCovered(s)) return loader.get();
        String pattern = blankToAll(namePattern);
        StringBuilder sql = new StringBuilder(
                "SELECT schema, name, type, remarks FROM snapshot_table WHERE schema = ? AND name LIKE ?");
        List<String> upperTypes = upperTypes(types);
        if (!upperTypes.isEmpty()) {
            sql.append(" AND UPPER(type) IN (").append(placeholders(upperTypes.size())).append(')');
        }
        sql.append(" ORDER BY name");
        List<TableEntry> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, s);
            ps.setString(2, pattern);
            int idx = 3;
            for (String t : upperTypes) ps.setString(idx++, t);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TableEntry(rs.getString("schema"), rs.getString("name"),
                            rs.getString("type"), rs.getString("remarks")));
                }
            }
        }
        return out;
    }

    @Override
    public TableDescription describeTable(String schema, String table,
                                          SqlSupplier<TableDescription> loader) throws SQLException {
        TableDescription cached = peekDescribeTable(schema, table);
        if (cached != null) return cached;
        TableDescription loaded = loader.get();
        if (loaded != null) saveAll(List.of(loaded));
        return loaded;
    }

    @Override
    public TableDescription peekDescribeTable(String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) return null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT detail_json FROM snapshot_table WHERE schema = ? AND name = ?")) {
            ps.setString(1, norm(schema));
            ps.setString(2, table.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return parseTable(rs.getString(1));
                }
            }
        }
        return null;
    }

    @Override
    public void saveAll(Collection<TableDescription> tables) throws SQLException {
        if (tables == null || tables.isEmpty()) return;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (TableDescription td : tables) {
                    if (td == null || td.name() == null) continue;
                    writeTable(conn, td);
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ---------- objects ----------

    @Override
    public List<Map<String, Object>> views(String schema,
                                           SqlSupplier<List<Map<String, Object>>> loader) throws SQLException {
        String s = norm(schema);
        if (!isCovered(s)) return loader.get();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT schema, name, definition FROM snapshot_view WHERE schema = ? ORDER BY name")) {
            ps.setString(1, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("schema", rs.getString("schema"));
                    row.put("name", rs.getString("name"));
                    row.put("definition", rs.getString("definition"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    @Override
    public String viewDefinition(String schema, String name,
                                 SqlSupplier<String> loader) throws SQLException {
        String s = norm(schema);
        if (!isCovered(s)) return loader.get();
        return scalar("SELECT definition FROM snapshot_view WHERE schema = ? AND name = ?", s, name);
    }

    @Override
    public List<RoutineEntry> routines(String schema, String namePattern,
                                       SqlSupplier<List<RoutineEntry>> loader) throws SQLException {
        String s = norm(schema);
        if (schema == null || schema.isBlank() || !isCovered(s)) return loader.get();
        String pattern = blankToAll(namePattern);
        List<RoutineEntry> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT schema, name, type FROM snapshot_routine "
                             + "WHERE schema = ? AND name LIKE ? ORDER BY name, type")) {
            ps.setString(1, s);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RoutineEntry(rs.getString("schema"), rs.getString("name"),
                            rs.getString("type")));
                }
            }
        }
        return out;
    }

    @Override
    public String routineSource(String schema, String name,
                                SqlSupplier<String> loader) throws SQLException {
        String s = norm(schema);
        if (!isCovered(s)) return loader.get();
        return scalar("SELECT source FROM snapshot_routine WHERE schema = ? AND name = ? "
                + "ORDER BY type FETCH FIRST 1 ROWS ONLY", s, name);
    }

    @Override
    public List<Trigger> triggers(String schema, boolean includeDefinition,
                                  SqlSupplier<List<Trigger>> loader) throws SQLException {
        String s = norm(schema);
        if (!isCovered(s)) return loader.get();
        List<Trigger> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT schema, table_name, name, timing, events, enabled, definition "
                             + "FROM snapshot_trigger WHERE schema = ? ORDER BY table_name, name")) {
            ps.setString(1, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String def = rs.getString("definition");
                    out.add(new Trigger(
                            rs.getString("schema"),
                            rs.getString("table_name"),
                            rs.getString("name"),
                            rs.getString("timing"),
                            splitCsv(rs.getString("events")),
                            rs.getInt("enabled") != 0,
                            includeDefinition ? def : null));
                }
            }
        }
        return out;
    }

    @Override
    public String triggerDefinition(String schema, String table, String trigger,
                                    SqlSupplier<String> loader) throws SQLException {
        String s = norm(schema);
        if (!isCovered(s)) return loader.get();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT definition FROM snapshot_trigger "
                             + "WHERE schema = ? AND table_name = ? AND name = ?")) {
            ps.setString(1, s);
            ps.setString(2, table == null ? "" : table);
            ps.setString(3, trigger);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    @Override
    public List<SequenceEntry> sequences(String schema,
                                         SqlSupplier<List<SequenceEntry>> loader) throws SQLException {
        String s = norm(schema);
        if (schema == null || schema.isBlank() || !isCovered(s)) return loader.get();
        List<SequenceEntry> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT schema, name FROM snapshot_sequence WHERE schema = ? ORDER BY name")) {
            ps.setString(1, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SequenceEntry(rs.getString("schema"), rs.getString("name")));
                }
            }
        }
        return out;
    }

    // ---------- derived ----------

    @Override
    public List<SearchObjectEntry> searchObjects(String namePattern,
                                                 SqlSupplier<List<SearchObjectEntry>> loader) throws SQLException {
        Set<String> covered = coveredSchemas();
        if (covered.isEmpty()) return loader.get();
        String pattern = (namePattern == null || namePattern.isBlank())
                ? "%"
                : namePattern.contains("%") ? namePattern : "%" + namePattern + "%";
        String in = placeholders(covered.size());
        String sql = ""
                + "SELECT schema, name, type FROM snapshot_table  WHERE schema IN (" + in + ") AND name LIKE ? "
                + "UNION ALL "
                + "SELECT schema, name, 'VIEW'     FROM snapshot_view     WHERE schema IN (" + in + ") AND name LIKE ? "
                + "UNION ALL "
                + "SELECT schema, name, type       FROM snapshot_routine  WHERE schema IN (" + in + ") AND name LIKE ? "
                + "UNION ALL "
                + "SELECT schema, name, 'SEQUENCE' FROM snapshot_sequence WHERE schema IN (" + in + ") AND name LIKE ? "
                + "ORDER BY name";
        List<SearchObjectEntry> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (int block = 0; block < 4; block++) {
                for (String sc : covered) ps.setString(idx++, sc);
                ps.setString(idx++, pattern);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SearchObjectEntry(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }

    @Override
    public List<TableEntry> findTablesByName(String name,
                                             SqlSupplier<List<TableEntry>> loader) throws SQLException {
        if (name == null || name.isBlank()) return List.of();
        Set<String> covered = coveredSchemas();
        if (covered.isEmpty()) return loader.get();
        String sql = "SELECT schema, name, type FROM snapshot_table "
                + "WHERE UPPER(name) = UPPER(?) AND schema IN (" + placeholders(covered.size()) + ")";
        List<TableEntry> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name.trim());
            int idx = 2;
            for (String sc : covered) ps.setString(idx++, sc);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TableEntry(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return out;
    }

    // ---------- build-all ----------

    @Override
    public void rebuild(StructureSnapshotData data) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String table : new String[]{"snapshot_foreign_key", "snapshot_column",
                        "snapshot_table", "snapshot_view", "snapshot_routine",
                        "snapshot_sequence", "snapshot_trigger"}) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                        ps.executeUpdate();
                    }
                }
                if (data.tables() != null) {
                    for (TableDescription td : data.tables()) {
                        if (td != null && td.name() != null) writeTable(conn, td);
                    }
                }
                writeViews(conn, data.views());
                writeRoutines(conn, data.routines());
                writeSequences(conn, data.sequences());
                writeTriggers(conn, data.triggers());

                long version = readVersion(conn) + 1;
                putMeta(conn, "snapshot_version", Long.toString(version));
                putMeta(conn, "structure_built_at", Instant.now().toString());
                putMeta(conn, "structure_schemas", csv(data.schemas()));

                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ---------- invalidation ----------

    @Override
    public void invalidateAll() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String table : new String[]{"snapshot_foreign_key", "snapshot_column",
                        "snapshot_table", "snapshot_view", "snapshot_routine",
                        "snapshot_sequence", "snapshot_trigger"}) {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table)) {
                        ps.executeUpdate();
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM catalog_meta WHERE meta_key IN ('structure_schemas', 'structure_built_at')")) {
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public void invalidateSchema(String schema) throws SQLException {
        if (schema == null) {
            invalidateAll();
            return;
        }
        String s = norm(schema);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                for (String table : new String[]{"snapshot_foreign_key", "snapshot_column",
                        "snapshot_table", "snapshot_view", "snapshot_routine",
                        "snapshot_sequence", "snapshot_trigger"}) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE schema = ?")) {
                        ps.setString(1, s);
                        ps.executeUpdate();
                    }
                }
                Set<String> covered = coveredSchemas(conn);
                covered.removeIf(sc -> sc.equalsIgnoreCase(s));
                putMeta(conn, "structure_schemas", csv(new ArrayList<>(covered)));
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    @Override
    public void invalidateTable(String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) return;
        String s = norm(schema);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                deleteTable(conn, s, table.trim());
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // ---------- write helpers ----------

    private void writeTable(Connection conn, TableDescription td) throws SQLException {
        String schema = norm(td.schema());
        String name = td.name();
        deleteTable(conn, schema, name);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO snapshot_table (schema, name, type, remarks, detail_json) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.setString(3, td.type());
            ps.setString(4, td.remarks());
            ps.setString(5, writeJson(td));
            ps.executeUpdate();
        }

        if (td.columns() != null && !td.columns().isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO snapshot_column (schema, table_name, ordinal, name, type_name, size, "
                            + "decimal_digits, nullable, column_default, remarks, auto_increment) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                Set<String> seen = new LinkedHashSet<>();
                for (Column c : td.columns()) {
                    if (c == null || c.name() == null || !seen.add(c.name())) continue;
                    ps.setString(1, schema);
                    ps.setString(2, name);
                    ps.setInt(3, c.ordinalPosition());
                    ps.setString(4, c.name());
                    ps.setString(5, c.typeName());
                    ps.setInt(6, c.size());
                    setNullableInt(ps, 7, c.decimalDigits());
                    ps.setInt(8, c.nullable() ? 1 : 0);
                    ps.setString(9, c.defaultValue());
                    ps.setString(10, c.remarks());
                    if (c.autoIncrement() == null) ps.setNull(11, Types.INTEGER);
                    else ps.setInt(11, c.autoIncrement() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        if (td.foreignKeys() != null && !td.foreignKeys().isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO snapshot_foreign_key (schema, table_name, name, ordinal, column_name, "
                            + "ref_schema, ref_table, ref_column) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (ForeignKey fk : td.foreignKeys()) {
                    if (fk == null || fk.referencedTable() == null || fk.columns() == null) continue;
                    List<String> cols = fk.columns();
                    List<String> refCols = fk.referencedColumns();
                    for (int i = 0; i < cols.size(); i++) {
                        if (cols.get(i) == null) continue;
                        ps.setString(1, schema);
                        ps.setString(2, name);
                        ps.setString(3, fk.name());
                        ps.setInt(4, i);
                        ps.setString(5, cols.get(i));
                        ps.setString(6, fk.referencedSchema());
                        ps.setString(7, fk.referencedTable());
                        ps.setString(8, refCols != null && i < refCols.size() ? refCols.get(i) : null);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
        }
    }

    private void deleteTable(Connection conn, String schema, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM snapshot_foreign_key WHERE schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM snapshot_column WHERE schema = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM snapshot_table WHERE schema = ? AND name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private void writeViews(Connection conn, List<ViewRecord> views) throws SQLException {
        if (views == null || views.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO snapshot_view KEY(schema, name) VALUES (?, ?, ?)")) {
            for (ViewRecord v : views) {
                if (v == null || v.name() == null) continue;
                ps.setString(1, norm(v.schema()));
                ps.setString(2, v.name());
                ps.setString(3, v.definition());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeRoutines(Connection conn, List<RoutineRecord> routines) throws SQLException {
        if (routines == null || routines.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO snapshot_routine KEY(schema, name, type) VALUES (?, ?, ?, ?)")) {
            for (RoutineRecord r : routines) {
                if (r == null || r.name() == null) continue;
                ps.setString(1, norm(r.schema()));
                ps.setString(2, r.name());
                ps.setString(3, r.type() == null ? "" : r.type());
                ps.setString(4, r.source());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeSequences(Connection conn, List<SequenceEntry> sequences) throws SQLException {
        if (sequences == null || sequences.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO snapshot_sequence KEY(schema, name) VALUES (?, ?)")) {
            for (SequenceEntry seq : sequences) {
                if (seq == null || seq.name() == null) continue;
                ps.setString(1, norm(seq.schema()));
                ps.setString(2, seq.name());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void writeTriggers(Connection conn, List<Trigger> triggers) throws SQLException {
        if (triggers == null || triggers.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO snapshot_trigger KEY(schema, table_name, name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (Trigger t : triggers) {
                if (t == null || t.name() == null) continue;
                ps.setString(1, norm(t.schema()));
                ps.setString(2, t.table() == null ? "" : t.table());
                ps.setString(3, t.name());
                ps.setString(4, t.timing());
                ps.setString(5, csv(t.events()));
                ps.setInt(6, t.enabled() ? 1 : 0);
                ps.setString(7, t.definition());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---------- catalog_meta ----------

    private void putMeta(Connection conn, String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO catalog_meta KEY(meta_key) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private long readVersion(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT meta_value FROM catalog_meta WHERE meta_key = 'snapshot_version'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                try {
                    return Long.parseLong(rs.getString(1).trim());
                } catch (NumberFormatException | NullPointerException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private boolean isCovered(String normalizedSchema) throws SQLException {
        Set<String> covered = coveredSchemas();
        if (covered.isEmpty()) return false;
        for (String sc : covered) {
            if (sc.equalsIgnoreCase(normalizedSchema)) return true;
        }
        return false;
    }

    private Set<String> coveredSchemas() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return coveredSchemas(conn);
        }
    }

    private Set<String> coveredSchemas(Connection conn) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT meta_value FROM catalog_meta WHERE meta_key = 'structure_schemas'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                for (String part : splitCsv(rs.getString(1))) {
                    out.add(norm(part));
                }
            }
        }
        return out;
    }

    // ---------- misc helpers ----------

    private TableDescription parseTable(String json) throws SQLException {
        try {
            return mapper.readValue(json, TableDescription.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to parse snapshot detail_json: " + e.getMessage(), e);
        }
    }

    private String writeJson(TableDescription td) throws SQLException {
        try {
            return mapper.writeValueAsString(td);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize TableDescription: " + e.getMessage(), e);
        }
    }

    private String scalar(String sql, String schema, String name) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER);
        else ps.setInt(index, value);
    }

    private static String norm(String schema) {
        return schema == null ? "" : schema.trim();
    }

    private static String blankToAll(String pattern) {
        return pattern == null || pattern.isBlank() ? "%" : pattern;
    }

    private static List<String> upperTypes(String[] types) {
        if (types == null || types.length == 0) return List.of();
        List<String> out = new ArrayList<>(types.length);
        for (String t : types) {
            if (t != null && !t.isBlank()) out.add(t.trim().toUpperCase(Locale.ROOT));
        }
        return out;
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static String csv(Collection<String> values) {
        if (values == null || values.isEmpty()) return "";
        Set<String> ordered = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String v : values) {
            if (v != null && !v.isBlank()) ordered.add(v.trim());
        }
        return String.join(",", ordered);
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }
}
