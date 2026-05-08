package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.FkIndexCoverage;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.RedundantIndexes;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.UnusedIndexes;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Object-level statistics aggregation. Wraps dialect-specific queries and does Java-side
 * set math for analyses that are independent of the storage engine:
 * <ul>
 *     <li>{@code fkIndexCoverage} — foreign keys that lack a supporting index on the child side;</li>
 *     <li>{@code redundantIndexes} — indexes whose column list is a strict prefix of another one;</li>
 *     <li>{@code unusedIndexes} — indexes with zero recorded scans (PG exposes this directly).</li>
 * </ul>
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final SqlExecutor executor;
    private final SqlDialect dialect;
    private final JdbcProperties properties;
    private final SchemaResolver schemaResolver;

    public StatsService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties) {
        this(executor, dialect, properties, new SchemaResolver(properties, executor, dialect));
    }

    @Autowired
    public StatsService(SqlExecutor executor, SqlDialect dialect, JdbcProperties properties,
                        SchemaResolver schemaResolver) {
        this.executor = executor;
        this.dialect = dialect;
        this.properties = properties;
        this.schemaResolver = schemaResolver;
    }

    // ---------------- tableStats ----------------

    public Map<String, Object> tableStats(String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must be provided");
        }
        String effectiveSchema = resolveSchema(schema);
        QueryResult r = executor.queryInternal(dialect.tableStatsQuery(),
                List.of(effectiveSchema == null ? "" : effectiveSchema, table), 1);
        if (r.rows().isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("schema", effectiveSchema);
            empty.put("table", table);
            empty.put("found", false);
            return empty;
        }
        Map<String, Object> row = new LinkedHashMap<>(r.rows().get(0));
        row.put("found", true);

        // On Oracle, enrich with a best-effort segment size lookup. Requires DBA_SEGMENTS privilege.
        String segQuery = dialect.segmentSizeQuery();
        if (segQuery != null) {
            try {
                QueryResult seg = executor.queryInternal(segQuery,
                        List.of(effectiveSchema == null ? "" : effectiveSchema, table), 1);
                if (!seg.rows().isEmpty()) {
                    Object bytes = seg.rows().get(0).get(seg.columns().get(0));
                    row.put("segment_bytes", bytes);
                }
            } catch (SQLException e) {
                log.debug("segmentSizeQuery failed (likely missing DBA_SEGMENTS privilege): {}",
                        e.getMessage());
                row.put("segment_bytes_error", e.getMessage());
            }
        }
        return row;
    }

    // ---------------- indexStats ----------------

    /** Raw per-index rows. {@code table} is optional — {@code null} means all indexes in the schema. */
    public QueryResult indexStats(String schema, String table) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        String t = (table == null || table.isBlank()) ? null : table;
        return executor.queryInternal(dialect.indexStatsQuery(),
                Arrays.asList(effectiveSchema == null ? "" : effectiveSchema, t, t), 5_000);
    }

    // ---------------- unusedIndexes ----------------

    /**
     * Indexes with {@code idx_scans = 0} (or equivalent). PK / UNIQUE-backing indexes are skipped
     * since dropping them would break the constraint.
     *
     * <p>On PostgreSQL {@code idx_scan} comes from {@code pg_stat_user_indexes} — note that the
     * counter is cumulative since the last stats reset and across all backends; an index "never
     * scanned" is only a strong hint if the database has been running the real workload long
     * enough. On Oracle the information is not exposed by {@code ALL_INDEXES}, so this tool
     * reports a diagnostic note instead of a list.
     */
    public UnusedIndexes unusedIndexes(String schema, Long minSizeBytes) throws SQLException {
        if (dialect.kind() == DatabaseKind.ORACLE) {
            return new UnusedIndexes(false, "Oracle does not publish per-index scan counters in ALL_INDEXES. " +
                    "Use DBA_INDEX_USAGE (12.2+) or enable monitoring with " +
                    "ALTER INDEX ... MONITORING USAGE and query V$OBJECT_USAGE.",
                    null, 0, List.of());
        }
        if (dialect.kind() == DatabaseKind.MSSQL) {
            return new UnusedIndexes(false, "SQL Server index usage counters come from sys.dm_db_index_usage_stats " +
                    "and require server/database state permissions. This tool does not report " +
                    "unused indexes for SQL Server from low-privilege metadata.",
                    null, 0, List.of());
        }
        QueryResult idx = indexStats(schema, null);
        List<UnusedIndexes.UnusedIndexEntry> unused = new ArrayList<>();
        for (Map<String, Object> row : idx.rows()) {
            long scans = toLong(getCI(row, "idx_scans"));
            boolean primary = toBool(getCI(row, "is_primary"));
            boolean unique  = toBool(getCI(row, "is_unique"));
            if (scans != 0) continue;
            if (primary || unique) continue;
            long size = toLong(getCI(row, "size_bytes"));
            if (minSizeBytes != null && size < minSizeBytes) continue;
            unused.add(new UnusedIndexes.UnusedIndexEntry(
                    String.valueOf(getCI(row, "schema")),
                    String.valueOf(getCI(row, "table_name")),
                    String.valueOf(getCI(row, "index_name")),
                    String.valueOf(getCI(row, "columns")),
                    size,
                    String.valueOf(getCI(row, "index_type"))));
        }
        unused.sort((a, b) -> Long.compare(b.sizeBytes(), a.sizeBytes()));
        return new UnusedIndexes(true, null, resolveSchema(schema), unused.size(), unused);
    }

    // ---------------- redundantIndexes ----------------

    /**
     * Finds indexes whose leading-column list is a strict prefix of another index on the same table.
     * The shorter index is a candidate for removal — the longer one already covers lookups on the
     * shared leading columns.
     *
     * <p>Rules:
     * <ul>
     *     <li>both indexes must be on the same {@code (schema, table)};</li>
     *     <li>shadowed index must be non-unique (dropping a UNIQUE index loses the constraint);</li>
     *     <li>index types must match (e.g. we don't claim a {@code gin} shadows a {@code btree});</li>
     *     <li>prefix match is strict — equal column lists are not reported (caller can pick either).</li>
     * </ul>
     */
    public RedundantIndexes redundantIndexes(String schema, String table) throws SQLException {
        QueryResult idx = indexStats(schema, table);
        // Group by (schema, table)
        Map<String, List<Map<String, Object>>> byTable = new TreeMap<>();
        for (Map<String, Object> r : idx.rows()) {
            String key = String.valueOf(getCI(r, "schema")) + "."
                    + String.valueOf(getCI(r, "table_name"));
            byTable.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<RedundantIndexes.Finding> findings = new ArrayList<>();
        for (List<Map<String, Object>> group : byTable.values()) {
            for (Map<String, Object> a : group) {
                List<String> aCols = splitColumns(getCI(a, "columns"));
                if (aCols.isEmpty()) continue;
                if (toBool(getCI(a, "is_unique")) || toBool(getCI(a, "is_primary"))) continue;
                String aType = strLower(getCI(a, "index_type"));
                for (Map<String, Object> b : group) {
                    if (a == b) continue;
                    List<String> bCols = splitColumns(getCI(b, "columns"));
                    if (bCols.size() <= aCols.size()) continue;
                    if (!aType.equals(strLower(getCI(b, "index_type")))) continue;
                    if (!bCols.subList(0, aCols.size()).equals(aCols)) continue;
                    findings.add(new RedundantIndexes.Finding(
                            String.valueOf(getCI(a, "schema")),
                            String.valueOf(getCI(a, "table_name")),
                            String.valueOf(getCI(a, "index_name")),
                            String.valueOf(getCI(a, "columns")),
                            toLong(getCI(a, "size_bytes")),
                            String.valueOf(getCI(b, "index_name")),
                            String.valueOf(getCI(b, "columns")),
                            String.valueOf(getCI(a, "index_type"))));
                    break;
                }
            }
        }
        return new RedundantIndexes(resolveSchema(schema), table, findings.size(), findings);
    }

    // ---------------- fkIndexCoverage ----------------

    /**
     * Reports every foreign key on the child (FK-holding) side that does not have a supporting
     * index whose leading columns match the FK column list. This is a classic cause of slow
     * {@code DELETE}/{@code UPDATE} cascades and slow joins against the parent table.
     *
     * <p>Uses {@code DatabaseMetaData.getImportedKeys} + {@code getIndexInfo} so it works the
     * same way on both engines. If {@code table} is null we scan every table in the schema.
     */
    public FkIndexCoverage fkIndexCoverage(String schema, String table) throws SQLException {
        String effectiveSchema = resolveSchema(schema);
        return executor.withConnection(conn -> {
            DatabaseMetaData md = conn.getMetaData();
            List<String> tables = table != null && !table.isBlank()
                    ? List.of(table)
                    : listUserTables(md, effectiveSchema);
            List<FkIndexCoverage.UncoveredEntry> uncovered = new ArrayList<>();
            int fkTotal = 0;
            for (String t : tables) {
                List<FkInfo> fks = fetchFks(md, effectiveSchema, t);
                fkTotal += fks.size();
                if (fks.isEmpty()) continue;
                List<List<String>> indexPrefixes = fetchIndexPrefixes(md, effectiveSchema, t);
                for (FkInfo fk : fks) {
                    if (isCovered(fk.columns, indexPrefixes)) continue;
                    uncovered.add(new FkIndexCoverage.UncoveredEntry(
                            effectiveSchema, t, fk.name, fk.columns,
                            fk.referencedSchema, fk.referencedTable, fk.referencedColumns,
                            fk.columns));
                }
            }
            return new FkIndexCoverage(effectiveSchema, table, tables.size(), fkTotal,
                    uncovered.size(), uncovered);
        });
    }

    // ---------------- helpers ----------------

    private List<String> listUserTables(DatabaseMetaData md, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, schema, "%", new String[]{"TABLE", "PARTITIONED TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private List<FkInfo> fetchFks(DatabaseMetaData md, String schema, String table) throws SQLException {
        Map<String, FkInfo> byName = new LinkedHashMap<>();
        try (ResultSet rs = md.getImportedKeys(null, schema, table)) {
            while (rs.next()) {
                String name = rs.getString("FK_NAME");
                if (name == null) name = "fk_anon_" + rs.getString("FKCOLUMN_NAME");
                FkInfo fk = byName.computeIfAbsent(name, FkInfo::new);
                fk.referencedSchema = rs.getString("PKTABLE_SCHEM");
                fk.referencedTable  = rs.getString("PKTABLE_NAME");
                short seq = rs.getShort("KEY_SEQ");
                fk.columnsRaw.add(new OrderedCol(seq, rs.getString("FKCOLUMN_NAME")));
                fk.referencedColumnsRaw.add(new OrderedCol(seq, rs.getString("PKCOLUMN_NAME")));
            }
        }
        List<FkInfo> out = new ArrayList<>(byName.values());
        for (FkInfo fk : out) fk.sortColumns();
        return out;
    }

    private List<List<String>> fetchIndexPrefixes(DatabaseMetaData md, String schema, String table)
            throws SQLException {
        Map<String, List<OrderedCol>> byIndex = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(null, schema, table, false, false)) {
            while (rs.next()) {
                String idx = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idx == null || col == null) continue;
                short pos = rs.getShort("ORDINAL_POSITION");
                byIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(new OrderedCol(pos, col));
            }
        }
        List<List<String>> prefixes = new ArrayList<>(byIndex.size());
        for (List<OrderedCol> cols : byIndex.values()) {
            cols.sort((a, b) -> Short.compare(a.pos, b.pos));
            List<String> names = new ArrayList<>(cols.size());
            for (OrderedCol c : cols) names.add(c.name);
            prefixes.add(names);
        }
        return prefixes;
    }

    /** The FK is covered iff any index has an ordered column list starting with the FK columns. */
    private boolean isCovered(List<String> fkCols, List<List<String>> indexes) {
        List<String> fkLower = toLowerAll(fkCols);
        for (List<String> idx : indexes) {
            if (idx.size() < fkCols.size()) continue;
            List<String> prefix = toLowerAll(idx.subList(0, fkCols.size()));
            if (prefix.equals(fkLower)) return true;
        }
        return false;
    }

    private List<String> toLowerAll(List<String> src) {
        List<String> out = new ArrayList<>(src.size());
        for (String s : src) out.add(s == null ? null : s.toLowerCase(Locale.ROOT));
        return out;
    }

    private String resolveSchema(String schema) throws SQLException {
        return schemaResolver.resolve(schema);
    }

    /**
     * Case-insensitive lookup against a row map. Oracle reports column labels in upper case;
     * PostgreSQL preserves the alias case from the query (lower case here). This helper lets
     * engine-agnostic code access columns by their lower-case canonical name.
     */
    private static Object getCI(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v != null) return v;
        String upper = key.toUpperCase(Locale.ROOT);
        v = row.get(upper);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0L;
        String s = v.toString().trim();
        return "t".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s)
                || "y".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                || "1".equals(s);
    }

    private static String strLower(Object v) {
        return v == null ? "" : v.toString().toLowerCase(Locale.ROOT);
    }

    private static List<String> splitColumns(Object v) {
        if (v == null) return Collections.emptyList();
        String s = v.toString();
        if (s.isBlank()) return Collections.emptyList();
        String[] parts = s.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private record OrderedCol(short pos, String name) {}

    private static final class FkInfo {
        final String name;
        String referencedSchema;
        String referencedTable;
        final List<OrderedCol> columnsRaw = new ArrayList<>();
        final List<OrderedCol> referencedColumnsRaw = new ArrayList<>();
        final List<String> columns = new ArrayList<>();
        final List<String> referencedColumns = new ArrayList<>();

        FkInfo(String name) { this.name = name; }

        void sortColumns() {
            columnsRaw.sort((a, b) -> Short.compare(a.pos, b.pos));
            referencedColumnsRaw.sort((a, b) -> Short.compare(a.pos, b.pos));
            for (OrderedCol c : columnsRaw) columns.add(c.name);
            for (OrderedCol c : referencedColumnsRaw) referencedColumns.add(c.name);
        }
    }
}
