package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.firebirdsql.jdbc.FirebirdPreparedStatement;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

/**
 * Firebird 3.0+ through Jaybird.
 *
 * <p>Firebird before 6.0 has no schemas: the database is presented as one logical schema named
 * {@value #LOGICAL_SCHEMA} (the schema Firebird 6 moves existing objects into), see
 * {@link SqlDialect#supportsSchemas()}. Every catalog query below still takes the schema parameter
 * its callers bind, and ignores it through {@link #ANY_SCHEMA}.
 *
 * <p>Other engine traits the tools depend on:
 * <ul>
 *     <li>Read-only is enforced by the server: Jaybird maps {@link Connection#setReadOnly} to a
 *         read-only transaction, which rejects DML and DDL.</li>
 *     <li>There is no EXPLAIN statement. Plans come from the prepared statement through Jaybird
 *         ({@link PlanCapture#DRIVER_API}) and carry no row estimates or costs.</li>
 *     <li>Catalog names are {@code CHAR} columns padded with blanks, hence the {@code TRIM}s.</li>
 *     <li>Statistics are limited to index selectivity ({@code RDB$STATISTICS}, as of the last
 *         {@code SET STATISTICS} / restore): row estimates are {@code 1 / selectivity} of the most
 *         selective unique index; there are no sizes or usage counters.</li>
 * </ul>
 */
public class FirebirdDialect implements SqlDialect {

    static final String LOGICAL_SCHEMA = "PUBLIC";

    /** SQL literal for {@link #LOGICAL_SCHEMA}. */
    private static final String SCHEMA_LITERAL = "CAST('" + LOGICAL_SCHEMA + "' AS VARCHAR(63))";

    /**
     * Consumes one bound schema parameter and is always true: callers bind the logical schema
     * (or {@code null}) positionally, and Firebird has nothing to filter it against.
     */
    private static final String ANY_SCHEMA = "(CAST(? AS VARCHAR(63)) IS NULL OR 1 = 1)";

    /** Firebird limits an index to 16 segments. */
    private static final int MAX_INDEX_SEGMENTS = 16;

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.FIREBIRD;
    }

    @Override
    public void prepareReadOnly(Connection connection) throws SQLException {
        // Jaybird starts read-only transactions for a read-only connection: the server rejects writes.
        if (!connection.isReadOnly()) {
            connection.setReadOnly(true);
        }
    }

    // ---------------- namespace ----------------

    @Override
    public boolean supportsSchemas() {
        return false;
    }

    @Override
    public String logicalSchema() {
        return LOGICAL_SCHEMA;
    }

    @Override
    public Connection wrapConnection(Connection connection) {
        return SchemalessConnections.wrap(connection, LOGICAL_SCHEMA);
    }

    @Override
    public String fallbackSchema(Connection connection) {
        return LOGICAL_SCHEMA;
    }

    @Override
    public List<String> systemSchemas() {
        return List.of();
    }

    /**
     * Unquoted identifiers fold to upper case. Upper-case names are quoted — they match the catalog
     * exactly and stay safe when they collide with a reserved word ({@code VALUE}, {@code POSITION});
     * anything else is left unquoted so {@code fias_house} still finds {@code FIAS_HOUSE}.
     */
    @Override
    public String quoteIdentifier(String identifier) {
        return identifier.equals(identifier.toUpperCase(java.util.Locale.ROOT))
                ? "\"" + identifier + "\""
                : identifier;
    }

    /** The logical schema is not a real one, so it never appears in generated SQL. */
    @Override
    public String qualify(String schema, String table) {
        return quoteIdentifier(table);
    }

    // ---------------- connection ----------------

    /**
     * A connection without a character set makes Firebird hand back raw bytes for text columns.
     * Unless the URL chooses one, ask for UTF8: the server transliterates from each column's own
     * character set (WIN1251 and so on) into what Java strings hold anyway.
     */
    @Override
    public String applyUrlTweaks(String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("encoding=") || lower.contains("charset=") || lower.contains("lc_ctype=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "encoding=UTF8";
    }

    @Override
    public String limitQuery(String sql, int limit) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("fetch first") || lower.contains("fetch next") || lower.contains(" rows ")
                || lower.startsWith("select first ") || lower.startsWith("select distinct first ")) {
            return trimmed;
        }
        return trimmed + "\nFETCH FIRST " + limit + " ROWS ONLY";
    }

    // ---------------- plans ----------------

    @Override
    public PlanCapture planCapture() {
        return PlanCapture.DRIVER_API;
    }

    /**
     * The explained (tree) plan of Firebird 3+, or the legacy one-line {@code PLAN ...} when the
     * server cannot explain.
     */
    @Override
    public String driverPlan(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            FirebirdPreparedStatement statement = ps.unwrap(FirebirdPreparedStatement.class);
            try {
                return statement.getExplainedExecutionPlan();
            } catch (SQLException e) {
                return statement.getExecutionPlan();
            }
        }
    }

    @Override
    public boolean plannerRowEstimates() {
        return false;
    }

    @Override
    public String buildExplain(String sql, boolean analyze) {
        throw new UnsupportedOperationException("Firebird has no EXPLAIN statement; use driverPlan");
    }

    @Override
    public String explainDisplayQuery() {
        return null;
    }

    @Override
    public String buildStructuredExplain(String sql, boolean analyze) {
        throw new UnsupportedOperationException("Firebird has no EXPLAIN statement; use driverPlan");
    }

    // ---------------- distribution ----------------

    /** Firebird 3 has no ordered-set aggregates; the rank-based computation is discrete. */
    @Override
    public String histogramPercentileFunction(boolean numeric) {
        return "percentile_disc";
    }

    /**
     * One pass with window functions: {@code ROW_NUMBER} over the non-null values, and each
     * percentile {@code p} is the smallest value whose rank reaches {@code CEILING(p * n)} — the
     * definition of {@code percentile_disc}.
     */
    @Override
    public String histogramQuery(String qualifiedTable, String quotedColumn, String percentileFunction) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) AS total_rows,
                       COUNT(v) AS non_null_rows,
                       MIN(v) AS min_value,
                       MAX(v) AS max_value""");
        String[][] percentiles = {{"0.25", "p25"}, {"0.5", "p50"}, {"0.75", "p75"},
                {"0.9", "p90"}, {"0.95", "p95"}, {"0.99", "p99"}};
        for (String[] p : percentiles) {
            sql.append(",\n       MIN(CASE WHEN rn >= CEILING(").append(p[0])
                    .append(" * cnt) THEN v END) AS ").append(p[1]);
        }
        sql.append("""

                FROM (
                    SELECT v,
                           CASE WHEN v IS NULL THEN NULL
                                ELSE ROW_NUMBER() OVER (
                                    PARTITION BY CASE WHEN v IS NULL THEN 1 ELSE 0 END ORDER BY v)
                           END AS rn,
                           COUNT(v) OVER () AS cnt
                    FROM (SELECT %s AS v FROM %s) b
                ) r""".formatted(quotedColumn, qualifiedTable));
        return sql.toString();
    }

    // ---------------- catalog ----------------

    @Override
    public String viewDefinitionQuery() {
        return """
                SELECT r.RDB$VIEW_SOURCE AS "definition"
                FROM RDB$RELATIONS r
                WHERE %s
                  AND r.RDB$RELATION_NAME = ?
                  AND r.RDB$VIEW_BLR IS NOT NULL
                """.formatted(ANY_SCHEMA);
    }

    @Override
    public String schemaViewsQuery() {
        return """
                SELECT %s AS "schema",
                       TRIM(r.RDB$RELATION_NAME) AS "name",
                       r.RDB$VIEW_SOURCE AS "definition"
                FROM RDB$RELATIONS r
                WHERE %s
                  AND r.RDB$VIEW_BLR IS NOT NULL
                  AND COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0
                ORDER BY 2
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA);
    }

    /**
     * Stored procedures, PSQL functions and package bodies. Legacy UDFs have no source; they are
     * described by their library entry point instead.
     */
    @Override
    public String routineSourceQuery() {
        return """
                SELECT src.def AS "definition"
                FROM (
                    SELECT TRIM(p.RDB$PROCEDURE_NAME) AS nm, p.RDB$PROCEDURE_SOURCE AS def
                    FROM RDB$PROCEDURES p
                    WHERE p.RDB$PACKAGE_NAME IS NULL
                    UNION ALL
                    SELECT TRIM(f.RDB$FUNCTION_NAME),
                           COALESCE(f.RDB$FUNCTION_SOURCE,
                                    'EXTERNAL FUNCTION: module ' || COALESCE(TRIM(f.RDB$MODULE_NAME), '?')
                                    || ', entry point ' || COALESCE(TRIM(f.RDB$ENTRYPOINT), '?'))
                    FROM RDB$FUNCTIONS f
                    WHERE f.RDB$PACKAGE_NAME IS NULL
                    UNION ALL
                    SELECT TRIM(k.RDB$PACKAGE_NAME), k.RDB$PACKAGE_BODY_SOURCE
                    FROM RDB$PACKAGES k
                ) src
                WHERE %s
                  AND src.nm = ?
                """.formatted(ANY_SCHEMA);
    }

    @Override
    public String searchObjectsQuery() {
        return """
                SELECT FIRST 200 o."schema", o."name", o."type", o."owner"
                FROM (
                    SELECT %1$s AS "schema",
                           TRIM(r.RDB$RELATION_NAME) AS "name",
                           CAST(CASE WHEN r.RDB$VIEW_BLR IS NULL THEN 'TABLE' ELSE 'VIEW' END AS VARCHAR(20)) AS "type",
                           TRIM(r.RDB$OWNER_NAME) AS "owner"
                    FROM RDB$RELATIONS r
                    WHERE COALESCE(r.RDB$SYSTEM_FLAG, 0) = 0
                    UNION ALL
                    SELECT %1$s, TRIM(p.RDB$PROCEDURE_NAME), CAST('PROCEDURE' AS VARCHAR(20)), TRIM(p.RDB$OWNER_NAME)
                    FROM RDB$PROCEDURES p
                    WHERE COALESCE(p.RDB$SYSTEM_FLAG, 0) = 0
                    UNION ALL
                    SELECT %1$s, TRIM(f.RDB$FUNCTION_NAME), CAST('FUNCTION' AS VARCHAR(20)), TRIM(f.RDB$OWNER_NAME)
                    FROM RDB$FUNCTIONS f
                    WHERE COALESCE(f.RDB$SYSTEM_FLAG, 0) = 0
                    UNION ALL
                    SELECT %1$s, TRIM(g.RDB$GENERATOR_NAME), CAST('SEQUENCE' AS VARCHAR(20)), TRIM(g.RDB$OWNER_NAME)
                    FROM RDB$GENERATORS g
                    WHERE COALESCE(g.RDB$SYSTEM_FLAG, 0) = 0
                ) o
                WHERE UPPER(o."name") LIKE UPPER(?) OR UPPER(o."name") LIKE UPPER(?)
                ORDER BY 2
                """.formatted(SCHEMA_LITERAL);
    }

    /** Current values need {@code GEN_ID(name, 0)} per generator, so {@code last_value} stays empty. */
    @Override
    public String listSequencesQuery() {
        return """
                SELECT %s AS "schema",
                       TRIM(g.RDB$GENERATOR_NAME) AS "name",
                       CAST(NULL AS BIGINT) AS "min_value",
                       CAST(NULL AS BIGINT) AS "max_value",
                       g.RDB$GENERATOR_INCREMENT AS "increment",
                       CAST(NULL AS BIGINT) AS "last_value"
                FROM RDB$GENERATORS g
                WHERE %s AND %s
                  AND COALESCE(g.RDB$SYSTEM_FLAG, 0) = 0
                ORDER BY 2
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA, ANY_SCHEMA);
    }

    @Override
    public String listRoutinesQuery() {
        return """
                SELECT FIRST 500 o."schema", o."name", o."kind", o."language"
                FROM (
                    SELECT %1$s AS "schema",
                           TRIM(p.RDB$PROCEDURE_NAME) AS "name",
                           CAST('PROCEDURE' AS VARCHAR(20)) AS "kind",
                           CAST('PSQL' AS VARCHAR(20)) AS "language"
                    FROM RDB$PROCEDURES p
                    WHERE COALESCE(p.RDB$SYSTEM_FLAG, 0) = 0 AND p.RDB$PACKAGE_NAME IS NULL
                    UNION ALL
                    SELECT %1$s, TRIM(f.RDB$FUNCTION_NAME), CAST('FUNCTION' AS VARCHAR(20)),
                           CAST(CASE WHEN f.RDB$LEGACY_FLAG = 1 THEN 'UDF'
                                     WHEN f.RDB$ENGINE_NAME IS NOT NULL THEN 'UDR'
                                     ELSE 'PSQL' END AS VARCHAR(20))
                    FROM RDB$FUNCTIONS f
                    WHERE COALESCE(f.RDB$SYSTEM_FLAG, 0) = 0 AND f.RDB$PACKAGE_NAME IS NULL
                    UNION ALL
                    SELECT %1$s, TRIM(k.RDB$PACKAGE_NAME), CAST('PACKAGE' AS VARCHAR(20)), CAST('PSQL' AS VARCHAR(20))
                    FROM RDB$PACKAGES k
                    WHERE COALESCE(k.RDB$SYSTEM_FLAG, 0) = 0
                ) o
                WHERE %2$s AND %2$s
                  AND (CAST(? AS VARCHAR(255)) IS NULL OR UPPER(o."name") LIKE UPPER(?))
                ORDER BY 2
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA);
    }

    // ---------------- statistics ----------------

    @Override
    public String tableStatsQuery() {
        return """
                SELECT %s AS "schema",
                       TRIM(r.RDB$RELATION_NAME) AS "table_name",
                       CAST(CASE COALESCE(r.RDB$RELATION_TYPE, 0)
                                WHEN 0 THEN 'TABLE'
                                WHEN 1 THEN 'VIEW'
                                WHEN 2 THEN 'EXTERNAL TABLE'
                                WHEN 4 THEN 'GLOBAL TEMPORARY'
                                WHEN 5 THEN 'GLOBAL TEMPORARY'
                                ELSE 'OTHER'
                            END AS VARCHAR(20)) AS "relkind",
                       (SELECT CAST(ROUND(1 / MIN(i.RDB$STATISTICS)) AS BIGINT)
                          FROM RDB$INDICES i
                         WHERE i.RDB$RELATION_NAME = r.RDB$RELATION_NAME
                           AND i.RDB$UNIQUE_FLAG = 1
                           AND i.RDB$STATISTICS > 0
                           AND COALESCE(i.RDB$INDEX_INACTIVE, 0) = 0) AS "estimated_rows"
                FROM RDB$RELATIONS r
                WHERE %s
                  AND r.RDB$RELATION_NAME = ?
                """.formatted(SCHEMA_LITERAL, ANY_SCHEMA);
    }

    @Override
    public String indexStatsQuery() {
        return """
                SELECT %s AS "schema",
                       TRIM(i.RDB$RELATION_NAME) AS "table_name",
                       TRIM(i.RDB$INDEX_NAME) AS "index_name",
                       CAST(CASE WHEN i.RDB$EXPRESSION_SOURCE IS NOT NULL THEN 'EXPRESSION'
                                 WHEN COALESCE(i.RDB$INDEX_TYPE, 0) = 1 THEN 'DESCENDING'
                                 ELSE 'ASCENDING' END AS VARCHAR(20)) AS "index_type",
                       CASE WHEN i.RDB$UNIQUE_FLAG = 1 THEN TRUE ELSE FALSE END AS "is_unique",
                       CASE WHEN rc.RDB$CONSTRAINT_TYPE = 'PRIMARY KEY' THEN TRUE ELSE FALSE END AS "is_primary",
                       CASE WHEN COALESCE(i.RDB$INDEX_INACTIVE, 0) = 0 THEN TRUE ELSE FALSE END AS "is_valid",
                       CAST(NULL AS BIGINT) AS "size_bytes",
                       CAST(NULL AS BIGINT) AS "idx_scans",
                       CASE WHEN i.RDB$STATISTICS > 0
                            THEN CAST(ROUND(1 / i.RDB$STATISTICS) AS BIGINT) END AS "distinct_keys",
                       %s AS "columns",
                       i.RDB$EXPRESSION_SOURCE AS "definition"
                FROM RDB$INDICES i
                LEFT JOIN RDB$RELATION_CONSTRAINTS rc ON rc.RDB$INDEX_NAME = i.RDB$INDEX_NAME
                WHERE %s
                  AND COALESCE(i.RDB$SYSTEM_FLAG, 0) = 0
                  AND (CAST(? AS VARCHAR(63)) IS NULL OR i.RDB$RELATION_NAME = ?)
                ORDER BY 2, 3
                """.formatted(SCHEMA_LITERAL, indexSegments("i.RDB$INDEX_NAME"), ANY_SCHEMA);
    }

    @Override
    public String unusedIndexesUnsupportedReason() {
        return "Firebird 3 keeps no per-index usage counters in its catalog; index reads are only "
                + "visible per attachment in the MON$ tables while a workload runs.";
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
     * PK / UNIQUE / FK / CHECK constraints. Key columns come from the backing index; a CHECK's
     * text lives in the source of the system triggers that enforce it.
     */
    private static String constraintsQuery(boolean wholeSchema) {
        return """
                SELECT %s
                       TRIM(rc.RDB$CONSTRAINT_NAME) AS "name",
                       CAST(CASE rc.RDB$CONSTRAINT_TYPE
                                WHEN 'PRIMARY KEY' THEN 'PRIMARY_KEY'
                                WHEN 'FOREIGN KEY' THEN 'FOREIGN_KEY'
                                ELSE TRIM(rc.RDB$CONSTRAINT_TYPE)
                            END AS VARCHAR(20)) AS "type",
                       %s AS "columns",
                       (SELECT FIRST 1 t.RDB$TRIGGER_SOURCE
                          FROM RDB$CHECK_CONSTRAINTS cc
                          JOIN RDB$TRIGGERS t ON t.RDB$TRIGGER_NAME = cc.RDB$TRIGGER_NAME
                         WHERE cc.RDB$CONSTRAINT_NAME = rc.RDB$CONSTRAINT_NAME) AS "definition",
                       CASE WHEN ref.RDB$RELATION_NAME IS NOT NULL THEN %s END AS "referenced_schema",
                       TRIM(ref.RDB$RELATION_NAME) AS "referenced_table",
                       %s AS "referenced_columns"
                FROM RDB$RELATION_CONSTRAINTS rc
                LEFT JOIN RDB$REF_CONSTRAINTS rf ON rf.RDB$CONSTRAINT_NAME = rc.RDB$CONSTRAINT_NAME
                LEFT JOIN RDB$RELATION_CONSTRAINTS ref ON ref.RDB$CONSTRAINT_NAME = rf.RDB$CONST_NAME_UQ
                WHERE %s
                  %s
                  AND rc.RDB$CONSTRAINT_TYPE IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY', 'CHECK')
                ORDER BY %s
                """.formatted(
                wholeSchema ? "TRIM(rc.RDB$RELATION_NAME) AS \"table_name\"," : "",
                indexSegments("rc.RDB$INDEX_NAME"),
                SCHEMA_LITERAL,
                indexSegments("ref.RDB$INDEX_NAME"),
                ANY_SCHEMA,
                wholeSchema ? "" : "AND rc.RDB$RELATION_NAME = ?",
                wholeSchema ? "1, 2" : "1");
    }

    /**
     * Comma-separated key columns of an index in segment order. {@code LIST} does not promise an
     * order, so each position is looked up on its own; Firebird allows at most 16 segments.
     */
    private static String indexSegments(String indexNameExpression) {
        StringJoiner parts = new StringJoiner(" || ", "CAST(", " AS VARCHAR(1100))");
        for (int position = 0; position < MAX_INDEX_SEGMENTS; position++) {
            String segment = "(SELECT TRIM(s.RDB$FIELD_NAME) FROM RDB$INDEX_SEGMENTS s WHERE s.RDB$INDEX_NAME = "
                    + indexNameExpression + " AND s.RDB$FIELD_POSITION = " + position + ")";
            parts.add(position == 0 ? segment : "COALESCE(',' || " + segment + ", '')");
        }
        return parts.toString();
    }

    // ---------------- triggers ----------------

    @Override
    public String tableTriggersQuery() {
        return triggersQuery("AND t.RDB$RELATION_NAME = ?", "3");
    }

    @Override
    public String schemaTriggersQuery() {
        return triggersQuery("", "2, 3");
    }

    @Override
    public String triggerDefinitionQuery() {
        return """
                SELECT t.RDB$TRIGGER_SOURCE AS "definition"
                FROM RDB$TRIGGERS t
                WHERE %s
                  AND t.RDB$RELATION_NAME = ?
                  AND t.RDB$TRIGGER_NAME = ?
                """.formatted(ANY_SCHEMA);
    }

    /**
     * DML triggers on tables and views, without the system triggers behind CHECK constraints.
     *
     * <p>{@code RDB$TRIGGER_TYPE} encodes a multi-event trigger: odd means BEFORE, and
     * {@code (type + 1) >> 1} holds up to three 2-bit event slots (1 INSERT, 2 UPDATE, 3 DELETE).
     */
    private static String triggersQuery(String tableFilter, String orderBy) {
        return """
                SELECT %s AS "schema",
                       TRIM(t.RDB$RELATION_NAME) AS "table_name",
                       TRIM(t.RDB$TRIGGER_NAME) AS "name",
                       CAST(CASE WHEN BIN_AND(t.RDB$TRIGGER_TYPE, 1) = 1 THEN 'BEFORE' ELSE 'AFTER' END
                            AS VARCHAR(10)) AS "timing",
                       CAST(TRIM(LEADING ',' FROM %s || %s || %s) AS VARCHAR(30)) AS "events",
                       CASE WHEN COALESCE(t.RDB$TRIGGER_INACTIVE, 0) = 0 THEN TRUE ELSE FALSE END AS "enabled",
                       t.RDB$TRIGGER_SOURCE AS "definition"
                FROM RDB$TRIGGERS t
                WHERE %s
                  %s
                  AND t.RDB$RELATION_NAME IS NOT NULL
                  AND t.RDB$TRIGGER_TYPE < 8192
                  AND COALESCE(t.RDB$SYSTEM_FLAG, 0) = 0
                  AND NOT EXISTS (SELECT 1 FROM RDB$CHECK_CONSTRAINTS cc
                                  WHERE cc.RDB$TRIGGER_NAME = t.RDB$TRIGGER_NAME)
                ORDER BY %s
                """.formatted(SCHEMA_LITERAL,
                triggerEvent(0), triggerEvent(2), triggerEvent(4),
                ANY_SCHEMA, tableFilter, orderBy);
    }

    private static String triggerEvent(int shift) {
        String slot = "BIN_AND(BIN_SHR(BIN_SHR(t.RDB$TRIGGER_TYPE + 1, 1), " + shift + "), 3)";
        return "CASE " + slot + " WHEN 1 THEN ',INSERT' WHEN 2 THEN ',UPDATE' WHEN 3 THEN ',DELETE' ELSE '' END";
    }
}
