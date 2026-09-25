package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQLite through the bundled sqlite-jdbc driver — the same one the server's own catalog uses, so
 * the SQLite version is the one that driver embeds.
 *
 * <ul>
 *     <li><b>Read-only by the database:</b> {@link #applyUrlTweaks} opens the file with
 *         {@code open_mode=1} ({@code SQLITE_OPEN_READONLY}): writes fail inside SQLite, and a mistyped
 *         path is an error instead of a new empty database. The driver rejects changing the read-only
 *         flag of an open connection, so the pool does not set it.</li>
 *     <li><b>One schema, {@value #MAIN}:</b> the JDBC driver reports no schemas, so the database is
 *         presented as {@code main}, the name SQLite itself uses — {@code main.orders} is valid SQL.
 *         Attached databases are not listed.</li>
 *     <li><b>Catalog:</b> {@code sqlite_schema} holds the full {@code CREATE} text of views and
 *         triggers; keys and indexes come from the {@code pragma_*} table-valued functions; sizes from
 *         {@code dbstat}. SQLite has no stored routines. CHECK constraints live only inside the
 *         {@code CREATE TABLE} text and are not reported.</li>
 *     <li><b>Plans:</b> {@code EXPLAIN QUERY PLAN}, which prepares but does not run the query, rendered
 *         as the tree the {@code sqlite3} shell prints; no costs or row estimates.</li>
 *     <li><b>Identifiers</b> are case-insensitive even when quoted, so they are always quoted.</li>
 * </ul>
 */
public class SqliteDialect implements SqlDialect {

    static final String MAIN = "main";

    /** Consumes one bound schema parameter and is always true; there is only {@value #MAIN}. */
    private static final String ANY_SCHEMA = "(? IS NULL OR 1 = 1)";

    private static final String SCHEMA_LITERAL = "'" + MAIN + "'";

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.SQLITE;
    }

    // ---------------- connection ----------------

    @Override
    public String applyUrlTweaks(String url) {
        if (url == null || url.toLowerCase(Locale.ROOT).contains("open_mode=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "open_mode=1";
    }

    @Override
    public boolean readOnlyPoolConnections() {
        return false;
    }

    /** {@code open_mode=1} already made the connection read-only; the driver refuses to change it. */
    @Override
    public void prepareReadOnly(Connection connection) {
    }

    // ---------------- namespace ----------------

    @Override
    public boolean supportsSchemas() {
        return false;
    }

    @Override
    public String logicalSchema() {
        return MAIN;
    }

    @Override
    public Connection wrapConnection(Connection connection) {
        return SchemalessConnections.wrap(connection, MAIN);
    }

    @Override
    public String fallbackSchema(Connection connection) {
        return MAIN;
    }

    @Override
    public List<String> systemSchemas() {
        return List.of();
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    @Override
    public String qualify(String schema, String table) {
        return quoteIdentifier(table);
    }

    @Override
    public String limitQuery(String sql, int limit) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains(" limit ") || lower.endsWith(" limit")) {
            return trimmed;
        }
        return trimmed + "\nLIMIT " + limit;
    }

    // ---------------- plans ----------------

    /** The plan rows form a tree by {@code id} / {@code parent}; it is rendered here, see {@link #driverPlan}. */
    @Override
    public PlanCapture planCapture() {
        return PlanCapture.DRIVER_API;
    }

    /**
     * {@code EXPLAIN QUERY PLAN} rendered like the {@code sqlite3} shell:
     * <pre>
     * QUERY PLAN
     * |--SEARCH t USING INDEX idx_name (name=?)
     * `--LIST SUBQUERY 1
     *    `--SCAN o
     * </pre>
     * Unbound {@code ?} placeholders are fine — the statement is only prepared.
     */
    @Override
    public String driverPlan(Connection connection, String sql) throws SQLException {
        Map<Integer, List<PlanRow>> children = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("EXPLAIN QUERY PLAN " + stripSemicolons(sql));
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlanRow row = new PlanRow(rs.getInt("id"), rs.getString("detail"));
                children.computeIfAbsent(rs.getInt("parent"), k -> new ArrayList<>()).add(row);
            }
        }
        StringBuilder out = new StringBuilder("QUERY PLAN\n");
        render(children, 0, "", out);
        return out.toString();
    }

    private record PlanRow(int id, String detail) {}

    private static void render(Map<Integer, List<PlanRow>> children, int parent, String indent, StringBuilder out) {
        List<PlanRow> rows = children.getOrDefault(parent, List.of());
        for (int i = 0; i < rows.size(); i++) {
            PlanRow row = rows.get(i);
            boolean last = i == rows.size() - 1;
            out.append(indent).append(last ? "`--" : "|--").append(row.detail()).append('\n');
            render(children, row.id(), indent + (last ? "   " : "|  "), out);
        }
    }

    @Override
    public boolean plannerRowEstimates() {
        return false;
    }

    @Override
    public String buildExplain(String sql, boolean analyze) {
        throw new UnsupportedOperationException("SQLite plans are rendered by driverPlan");
    }

    @Override
    public String explainDisplayQuery() {
        return null;
    }

    @Override
    public String buildStructuredExplain(String sql, boolean analyze) {
        throw new UnsupportedOperationException("SQLite plans are rendered by driverPlan");
    }

    // ---------------- distribution ----------------

    @Override
    public String histogramPercentileFunction(boolean numeric) {
        return "percentile_disc";
    }

    /** See {@link RankPercentiles}; window functions are available since SQLite 3.25. */
    @Override
    public String histogramQuery(String qualifiedTable, String quotedColumn, String percentileFunction) {
        return RankPercentiles.histogramQuery(qualifiedTable, quotedColumn);
    }

    // ---------------- catalog ----------------

    /** The full {@code CREATE VIEW} text, as SQLite stores it. */
    @Override
    public String viewDefinitionQuery() {
        return """
                SELECT sql AS definition
                FROM sqlite_schema
                WHERE %s AND type = 'view' AND name = ? COLLATE NOCASE
                """.formatted(ANY_SCHEMA);
    }

    @Override
    public String schemaViewsQuery() {
        return """
                SELECT %s AS schema, name, sql AS definition
                FROM sqlite_schema
                WHERE %s AND type = 'view'
                ORDER BY name
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA);
    }

    /** SQLite has no stored routines; {@code null} makes the routine tools report that. */
    @Override
    public String routineSourceQuery() {
        return null;
    }

    @Override
    public String listRoutinesQuery() {
        return null;
    }

    @Override
    public String searchObjectsQuery() {
        return """
                SELECT %s AS schema, name, upper(type) AS type, NULL AS owner
                FROM sqlite_schema
                WHERE type IN ('table', 'view', 'trigger')
                  AND name NOT LIKE 'sqlite\\_%%' ESCAPE '\\'
                  AND (name LIKE ? OR name LIKE ?)
                ORDER BY name
                LIMIT 200
                """.formatted(SCHEMA_LITERAL);
    }

    /**
     * {@code AUTOINCREMENT} counters, one per table that has one, named after the table. The counter
     * values live in {@code sqlite_sequence}, which exists only once such a table was created, so
     * {@code last_value} stays empty.
     */
    @Override
    public String listSequencesQuery() {
        return """
                SELECT %s AS schema, name, NULL AS min_value, NULL AS max_value,
                       1 AS increment, NULL AS last_value
                FROM sqlite_schema
                WHERE %s AND %s
                  AND type = 'table' AND upper(sql) LIKE '%%AUTOINCREMENT%%'
                ORDER BY name
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA, ANY_SCHEMA);
    }

    // ---------------- statistics ----------------

    /**
     * Sizes from {@code dbstat}. SQLite keeps row counts only in {@code sqlite_stat1}, which exists
     * after {@code ANALYZE} alone, so {@code estimated_rows} is left for an exact count.
     */
    @Override
    public String tableStatsQuery() {
        return """
                SELECT %s AS schema, m.name AS table_name, upper(m.type) AS relkind,
                       NULL AS estimated_rows,
                       COALESCE(t.bytes, 0) + COALESCE(i.bytes, 0) AS total_size_bytes,
                       t.bytes AS table_size_bytes,
                       i.bytes AS indexes_size_bytes
                FROM sqlite_schema m
                LEFT JOIN (SELECT name, SUM(pgsize) AS bytes FROM dbstat GROUP BY name) t ON t.name = m.name
                LEFT JOIN (SELECT x.tbl_name, SUM(d.pgsize) AS bytes
                             FROM dbstat d JOIN sqlite_schema x ON x.name = d.name AND x.type = 'index'
                            GROUP BY x.tbl_name) i ON i.tbl_name = m.name
                WHERE %s AND m.type IN ('table', 'view') AND m.name = ? COLLATE NOCASE
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA);
    }

    @Override
    public String indexStatsQuery() {
        return """
                SELECT %s AS schema, t.name AS table_name, il.name AS index_name,
                       CASE WHEN il.partial THEN 'PARTIAL' ELSE 'BTREE' END AS index_type,
                       il."unique" AS is_unique,
                       il.origin = 'pk' AS is_primary,
                       1 AS is_valid,
                       (SELECT SUM(d.pgsize) FROM dbstat d WHERE d.name = il.name) AS size_bytes,
                       NULL AS idx_scans,
                       (SELECT group_concat(ii.name, ',' ORDER BY ii.seqno)
                          FROM pragma_index_info(il.name) ii) AS columns,
                       x.sql AS definition
                FROM sqlite_schema t
                JOIN pragma_index_list(t.name) il
                LEFT JOIN sqlite_schema x ON x.type = 'index' AND x.name = il.name
                WHERE %s AND t.type = 'table'
                  AND (? IS NULL OR t.name = ? COLLATE NOCASE)
                ORDER BY 2, 3
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA);
    }

    @Override
    public String unusedIndexesUnsupportedReason() {
        return "SQLite keeps no index usage statistics.";
    }

    // ---------------- constraints ----------------

    @Override
    public String tableConstraintsQuery() {
        return constraintsQuery(false);
    }

    @Override
    public String schemaConstraintsQuery() {
        return constraintsQuery(true);
    }

    /**
     * Primary keys ({@code pragma_table_info}), UNIQUE constraints (their automatic indexes) and
     * foreign keys ({@code pragma_foreign_key_list}; a reference without columns points at the
     * parent's primary key). SQLite keeps no constraint names, so the primary key has none and
     * foreign keys are named {@code fk_<table>_<n>}.
     */
    private static String constraintsQuery(boolean wholeSchema) {
        String tableColumn = wholeSchema ? "tbl.name AS table_name, " : "";
        return """
                WITH tbl AS (
                    SELECT name FROM sqlite_schema
                    WHERE %s AND type = 'table' AND name NOT LIKE 'sqlite\\_%%' ESCAPE '\\' %s
                )
                SELECT %sNULL AS name, 'PRIMARY_KEY' AS type,
                       (SELECT group_concat(p.name, ',' ORDER BY p.pk)
                          FROM pragma_table_info(tbl.name) p WHERE p.pk > 0) AS columns,
                       NULL AS definition, NULL AS referenced_schema, NULL AS referenced_table,
                       NULL AS referenced_columns
                FROM tbl
                WHERE EXISTS (SELECT 1 FROM pragma_table_info(tbl.name) p WHERE p.pk > 0)
                UNION ALL
                SELECT %sil.name, 'UNIQUE',
                       (SELECT group_concat(ii.name, ',' ORDER BY ii.seqno) FROM pragma_index_info(il.name) ii),
                       NULL, NULL, NULL, NULL
                FROM tbl JOIN pragma_index_list(tbl.name) il
                WHERE il.origin = 'u'
                UNION ALL
                SELECT %s'fk_' || tbl.name || '_' || fk.id, 'FOREIGN_KEY',
                       group_concat(fk."from", ',' ORDER BY fk.seq),
                       NULL, %s, fk."table",
                       group_concat(COALESCE(fk."to",
                                             (SELECT p.name FROM pragma_table_info(fk."table") p
                                               WHERE p.pk = fk.seq + 1)), ',' ORDER BY fk.seq)
                FROM tbl JOIN pragma_foreign_key_list(tbl.name) fk
                GROUP BY tbl.name, fk.id, fk."table"
                ORDER BY %s
                """.formatted(ANY_SCHEMA, wholeSchema ? "" : "AND name = ? COLLATE NOCASE",
                tableColumn, tableColumn, tableColumn, SCHEMA_LITERAL,
                wholeSchema ? "1, 2" : "1");
    }

    // ---------------- triggers ----------------

    @Override
    public String tableTriggersQuery() {
        return triggersQuery("AND tbl_name = ? COLLATE NOCASE");
    }

    @Override
    public String schemaTriggersQuery() {
        return triggersQuery("");
    }

    @Override
    public String triggerDefinitionQuery() {
        return """
                SELECT sql AS definition
                FROM sqlite_schema
                WHERE %s AND type = 'trigger'
                  AND tbl_name = ? COLLATE NOCASE AND name = ? COLLATE NOCASE
                """.formatted(ANY_SCHEMA);
    }

    /**
     * Timing and event are read from the {@code CREATE TRIGGER} header — the text before
     * {@code ON <table>}. SQLite fires a trigger without a timing keyword {@code BEFORE}, and a
     * trigger handles exactly one event.
     */
    private static String triggersQuery(String tableFilter) {
        return """
                SELECT %s AS schema, tbl_name AS table_name, name,
                       CASE WHEN hdr LIKE '%% INSTEAD OF %%' THEN 'INSTEAD OF'
                            WHEN hdr LIKE '%% AFTER %%' THEN 'AFTER'
                            ELSE 'BEFORE' END AS timing,
                       CASE WHEN hdr LIKE '%% INSERT %%' THEN 'INSERT'
                            WHEN hdr LIKE '%% UPDATE %%' THEN 'UPDATE'
                            WHEN hdr LIKE '%% DELETE %%' THEN 'DELETE' END AS events,
                       1 AS enabled,
                       sql AS definition
                FROM (SELECT name, tbl_name, sql,
                             ' ' || upper(substr(sql, 1, instr(upper(sql), ' ON '))) AS hdr
                        FROM sqlite_schema WHERE type = 'trigger') tr
                WHERE %s %s
                ORDER BY tbl_name, name
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA, tableFilter);
    }

    private static String stripSemicolons(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
