package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Database-specific SQL generation and fix-ups. Each concrete implementation knows how to:
 * <ul>
 *     <li>build an {@code EXPLAIN} statement; PostgreSQL returns rows directly, Oracle
 *         writes a static plan into {@code PLAN_TABLE}, and SQL Server uses session-scoped
 *         {@code SHOWPLAN} in the tool layer;</li>
 *     <li>fetch view / routine / sequence definitions via engine-specific catalogs;</li>
 *     <li>apply pagination to a user query (for {@code sampleRows} and output truncation);</li>
 *     <li>prepare a connection for read-only usage (typically {@code setReadOnly(true)}).</li>
 * </ul>
 */
public interface SqlDialect {

    /**
     * The dialect for a detected engine — the single place that maps a kind to its implementation.
     * A {@link GenericDialect} from here has no data source to learn the database's traits from;
     * it only answers the pool-building questions ({@link #applyUrlTweaks}, {@link #dataSourceProperties},
     * {@link #readOnlyPoolConnections}). {@code DialectConfig} builds the full one per connection.
     */
    static SqlDialect forKind(DatabaseKind kind) {
        return switch (kind) {
            case POSTGRESQL -> new PostgresDialect();
            case ORACLE -> new OracleDialect();
            case MSSQL -> new SqlServerDialect();
            case FIREBIRD -> new FirebirdDialect();
            case SQLITE -> new SqliteDialect();
            case GENERIC -> new GenericDialect(null);
        };
    }

    DatabaseKind kind();

    /** How the plan tools obtain an execution plan from this engine. */
    enum PlanCapture {
        /**
         * The plan comes from executing {@link #buildExplain} / {@link #buildStructuredExplain},
         * optionally followed by {@link #explainDisplayQuery} / {@link #structuredPlanQuery}.
         */
        EXPLAIN_STATEMENT,
        /**
         * The plan comes from running the query itself on a session switched into a plan-only
         * mode ({@code SET SHOWPLAN_TEXT / SHOWPLAN_XML ON} on SQL Server).
         */
        SESSION_SHOWPLAN,
        /**
         * The engine has no EXPLAIN statement; the driver prepares the query and reports its plan
         * through a vendor API ({@link #driverPlan}). Firebird via Jaybird.
         */
        DRIVER_API,
        /** No plans at all (generic JDBC): plan tools answer {@code unsupported}. */
        UNSUPPORTED
    }

    default PlanCapture planCapture() {
        return PlanCapture.EXPLAIN_STATEMENT;
    }

    /**
     * Prepare {@code sql} (positional {@code ?} placeholders only) without executing it and return
     * the engine's textual plan. Only called when {@link #planCapture()} is {@link PlanCapture#DRIVER_API}.
     */
    default String driverPlan(Connection connection, String sql) throws SQLException {
        throw new UnsupportedOperationException(kind() + " plans are not captured through the driver");
    }

    /**
     * Whether plans carry the optimizer's row estimates. When {@code false}, the selectivity and
     * join-cardinality tools fall back to exact {@code COUNT(*)} queries, which execute.
     */
    default boolean plannerRowEstimates() {
        return true;
    }

    /**
     * Whether the engine has schemas. When {@code false}, the whole database is presented as one
     * logical schema named {@link #logicalSchema()}: tools accept and report that name,
     * {@link #qualify} leaves it out of generated SQL, and {@link #wrapConnection} hides the
     * {@code null} schema columns of {@link java.sql.DatabaseMetaData} behind it.
     */
    default boolean supportsSchemas() {
        return true;
    }

    /** The single schema name of a schemaless engine; {@code null} for engines with schemas. */
    default String logicalSchema() {
        return null;
    }

    /**
     * Wrap a pooled connection before metadata code sees it. Schemaless engines return a
     * connection whose {@link java.sql.DatabaseMetaData} speaks in terms of {@link #logicalSchema()}.
     */
    default Connection wrapConnection(Connection connection) {
        return connection;
    }

    /**
     * Adjust the JDBC URL before the pool is built — e.g. PostgreSQL appends
     * {@code default_transaction_read_only=on}. Must leave the URL untouched when the user
     * already set the relevant option.
     */
    default String applyUrlTweaks(String url) {
        return url;
    }

    /**
     * Whether the pool marks every connection read-only. Drivers that reject
     * {@link Connection#setReadOnly} on an open connection would fail every checkout; their dialect
     * returns {@code false} and applies it best-effort in {@link #prepareReadOnly}.
     */
    default boolean readOnlyPoolConnections() {
        return true;
    }

    /** Driver properties the pool always sets for this engine (e.g. Oracle {@code remarksReporting}). */
    default Map<String, String> dataSourceProperties() {
        return Map.of();
    }

    /**
     * Quote an already validated simple identifier (letters, digits, {@code _ $ #}) so that it
     * resolves to the object the catalog reports. Oracle returns it unquoted, because unquoted
     * identifiers fold to upper case there and quoting would make the lookup case-sensitive.
     */
    default String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }

    /** {@code schema.table} with both parts quoted; just the table when {@code schema} is blank. */
    default String qualify(String schema, String table) {
        if (schema == null || schema.isBlank()) {
            return quoteIdentifier(table);
        }
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    /**
     * The percentile function {@link #histogramQuery} computes for a column: interpolated
     * {@code percentile_cont} for numeric columns, {@code percentile_disc} otherwise.
     */
    default String histogramPercentileFunction(boolean numeric) {
        return numeric ? "percentile_cont" : "percentile_disc";
    }

    /**
     * Single-row percentile summary for {@code columnHistogram}, or {@code null} when the engine
     * has no SQL for it — the percentiles are then computed client-side from a sorted scan.
     * Expected columns:
     * {@code total_rows}, {@code non_null_rows}, {@code min_value}, {@code max_value},
     * {@code p25}, {@code p50}, {@code p75}, {@code p90}, {@code p95}, {@code p99}.
     *
     * @param qualifiedTable      output of {@link #qualify}
     * @param quotedColumn        output of {@link #quoteIdentifier}
     * @param percentileFunction  {@code percentile_cont} or {@code percentile_disc}
     */
    default String histogramQuery(String qualifiedTable, String quotedColumn, String percentileFunction) {
        String p = percentileFunction;
        String c = quotedColumn;
        return "SELECT COUNT(*) AS total_rows, " +
                "COUNT(" + c + ") AS non_null_rows, " +
                "MIN(" + c + ") AS min_value, " +
                "MAX(" + c + ") AS max_value, " +
                p + "(0.25) WITHIN GROUP (ORDER BY " + c + ") AS p25, " +
                p + "(0.5)  WITHIN GROUP (ORDER BY " + c + ") AS p50, " +
                p + "(0.75) WITHIN GROUP (ORDER BY " + c + ") AS p75, " +
                p + "(0.9)  WITHIN GROUP (ORDER BY " + c + ") AS p90, " +
                p + "(0.95) WITHIN GROUP (ORDER BY " + c + ") AS p95, " +
                p + "(0.99) WITHIN GROUP (ORDER BY " + c + ") AS p99 " +
                "FROM " + qualifiedTable;
    }

    /**
     * Why {@code unusedIndexes} cannot answer on this engine, or {@code null} when it can
     * (the answer is then derived from the {@code idx_scans} column of {@link #indexStatsQuery()}).
     */
    default String unusedIndexesUnsupportedReason() {
        return null;
    }

    /**
     * Apply engine-specific read-only hardening to a checked-out connection. Called right before
     * each user query. Implementations should be idempotent and cheap (it runs per-query).
     */
    void prepareReadOnly(Connection connection) throws SQLException;

    /**
     * Wrap a user SELECT so that only the first {@code limit} rows are fetched.
     * Does not modify queries that already include their own LIMIT/ROWNUM/FETCH FIRST.
     */
    String limitQuery(String sql, int limit);

    /**
     * Build an EXPLAIN statement for the given SELECT. The returned SQL, when executed,
     * must produce a multi-row result whose textual representation is the query plan.
     *
     * @param analyze if {@code true} and supported (PG), collect actual run-time statistics
     *                (note: this executes the query!). Oracle and SQL Server ignore this flag
     *                and return static / estimated plans.
     */
    String buildExplain(String sql, boolean analyze);

    /**
     * Build an EXPLAIN statement using a caller-provided statement identifier when the dialect
     * stores plans out-of-band. Dialects that return the plan directly can ignore the identifier.
     */
    default String buildExplain(String sql, boolean analyze, String statementId) {
        return buildExplain(sql, analyze);
    }

    /**
     * Some dialects (Oracle) execute EXPLAIN in two steps: the {@code buildExplain()}
     * statement populates a plan table, then this statement reads the result. For PostgreSQL
     * this returns {@code null} — the EXPLAIN itself produces the rows.
     */
    String explainDisplayQuery();

    /**
     * Display query for an EXPLAIN statement scoped by {@code statementId}. Dialects that do not
     * store plans out-of-band can ignore the identifier.
     */
    default String explainDisplayQuery(String statementId) {
        return explainDisplayQuery();
    }

    /**
     * Build an EXPLAIN that yields a <b>machine-readable</b> plan — JSON on PostgreSQL,
     * a prepared {@code PLAN_TABLE} population on Oracle, or a SQL Server query to be run
     * under session-scoped {@code SHOWPLAN_XML}. The output is meant to be parsed by
     * {@link ru.it_spectrum.ai.jdbc.mcp.plan.PlanParser}, not displayed as-is.
     *
     * @param analyze PG only — run {@code EXPLAIN ANALYZE} for actual row/timing stats
     *                (the query is executed). Oracle always produces a static plan.
     */
    String buildStructuredExplain(String sql, boolean analyze);

    /**
     * Build a machine-readable EXPLAIN using a caller-provided statement identifier when the
     * dialect stores plans out-of-band.
     */
    default String buildStructuredExplain(String sql, boolean analyze, String statementId) {
        return buildStructuredExplain(sql, analyze);
    }

    /**
     * Two-step structured EXPLAIN (Oracle): the {@code buildStructuredExplain()} statement
     * populates {@code PLAN_TABLE}; this SELECT returns the typed columns the parser needs.
     * On PostgreSQL returns {@code null} — the FORMAT JSON EXPLAIN itself yields the row.
     */
    default String structuredPlanQuery() {
        return null;
    }

    /**
     * Structured plan query scoped by {@code statementId}. Dialects that return structured plans
     * directly can ignore the identifier.
     */
    default String structuredPlanQuery(String statementId) {
        return structuredPlanQuery();
    }

    /** SQL that returns the definition of a view ({@code schema}, {@code name}). */
    String viewDefinitionQuery();

    /**
     * SQL that returns ALL view definitions for a schema (bulk variant).
     * Expected columns: {@code schema}, {@code name}, {@code definition}.
     * Params: {@code (schema)} only.
     * When this returns non-null, the usage catalog uses it instead of iterating per-view.
     */
    default String schemaViewsQuery() {
        return null;
    }

    /** SQL that returns the source of a routine ({@code schema}, {@code name}). */
    String routineSourceQuery();

    /** SQL that returns {@code (schema, name, type, owner)} rows for a case-insensitive name pattern. */
    String searchObjectsQuery();

    /** SQL that returns {@code (schema, name, min_value, max_value, increment, last_value)} for sequences. */
    String listSequencesQuery();

    /** SQL that returns {@code (schema, name, kind, language)} for routines (functions / procedures). */
    String listRoutinesQuery();

    /**
     * Per-table statistics: row-count estimate, size, dead tuples, last analyze/vacuum, scan counters.
     * Params: {@code (schema, table)}. Both expected to be non-null.
     * Column set is engine-specific — callers render it as-is.
     */
    String tableStatsQuery();

    /**
     * Per-index statistics: size, usage counters, columns, uniqueness, cardinality.
     * Params: {@code (schema, table, table)} — if table is {@code null} the query returns all
     * indexes in the schema (the second occurrence is for the {@code ? IS NULL OR ... = ?} pattern).
     * Column set is engine-specific.
     */
    String indexStatsQuery();

    /**
     * Optional per-segment size lookup (Oracle uses {@code DBA_SEGMENTS}). Returns {@code null}
     * on engines where sizing is already included in {@link #tableStatsQuery()} (PostgreSQL).
     * Params: {@code (schema, name)}. The query must return a single column named {@code SEGMENT_BYTES}.
     */
    default String segmentSizeQuery() {
        return null;
    }

    /**
     * SQL that returns a non-empty result iff the {@code pg_stat_statements}-style extension is
     * installed. Returns {@code null} on engines that do not expose per-query timing/buffers
     * counters in this form.
     *
     * <p>Used by benchmarking tools to decide whether a before/after diff is possible.
     */
    default String pgStatStatementsAvailabilityQuery() {
        return null;
    }

    /**
     * SQL that snapshots {@code pg_stat_statements}-style counters for the current database.
     * Expected columns: {@code queryid} (text), {@code query} (text), {@code calls} (long),
     * {@code total_exec_time_ms} (double), {@code rows} (long), {@code shared_blks_hit} (long),
     * {@code shared_blks_read} (long). Returns {@code null} on engines that do not publish this.
     *
     * <p>The query itself must be static and safe to call from metadata flows.
     */
    default String pgStatStatementsSnapshotQuery() {
        return null;
    }

    /**
     * SQL that returns comments for every column of a given table.
     * Expected columns: {@code column_name} (text), {@code comment} (text).
     * Params: {@code (schema, table)}.
     *
     * <p>Oracle returns this via {@code ALL_COL_COMMENTS}; PostgreSQL uses
     * {@code information_schema.columns} where {@code column_default} is already VARCHAR.
     * Return {@code null} if the dialect has no such query (in which case
     * {@code describeTable} will not populate the {@code remarks} field).
     */
    default String columnCommentsQuery() {
        return null;
    }

    /**
     * SQL that returns default values for every column of a given table.
     * Expected columns: {@code column_name} (text), {@code default_value} (text).
     * Params: {@code (schema, table)}.
     *
     * <p>Oracle uses {@code ALL_TAB_COLUMNS.DATA_DEFAULT} (CLOB → VARCHAR safe via RTRIM).
     * PostgreSQL uses {@code information_schema.columns.column_default} directly.
     * Return {@code null} if the dialect has no such query (in which case
     * {@code describeTable} will not populate the {@code default} field).
     */
    default String columnDefaultsQuery() {
        return null;
    }

    /**
     * Optional combined query that returns comments <em>and</em> default values for every
     * column in one roundtrip. Expected columns:
     * {@code column_name} (text), {@code comment} (text), {@code default_value} (text).
     * Params: {@code (schema, table)}.
     *
     * <p>When this returns non-null, {@code describeTable} uses it instead of calling
     * {@link #columnCommentsQuery()} and {@link #columnDefaultsQuery()} separately.
     * Return {@code null} if the dialect has no such combined query.
     */
    default String columnMetadataQuery() {
        return null;
    }

    /**
     * SQL that returns table constraints. Expected columns:
     * {@code name}, {@code type}, {@code columns}, {@code definition},
     * {@code referenced_schema}, {@code referenced_table}, {@code referenced_columns}.
     * Column lists may be returned as comma-separated strings; callers normalize them.
     * Params: {@code (schema, table)}.
     */
    default String tableConstraintsQuery() {
        return null;
    }

    /**
     * SQL that returns imported foreign keys (child-side FKs referencing other tables).
     * Expected columns: {@code FK_NAME}, {@code FKCOLUMN_NAME},
     * {@code PKTABLE_SCHEM}, {@code PKTABLE_NAME}, {@code PKCOLUMN_NAME}.
     * Rows should be ordered by FK_NAME then KEY_SEQ (position).
     * Params: {@code (schema, table)}.
     *
     * <p>When non-null, {@code describeTable} uses this instead of the JDBC
     * {@code DatabaseMetaData.getImportedKeys()} call, which can be slow on Oracle.
     */
    default String importedKeysQuery() {
        return null;
    }

    /**
     * SQL that returns exported foreign keys (FKs in other tables referencing this one).
     * Expected columns: {@code FK_NAME}, {@code FKCOLUMN_NAME},
     * {@code FKTABLE_SCHEM}, {@code FKTABLE_NAME}, {@code PKCOLUMN_NAME}.
     * Rows should be ordered by FK_NAME then KEY_SEQ (position).
     * Params: {@code (schema, table)}.
     *
     * <p>When non-null, {@code describeTable} uses this instead of the JDBC
     * {@code DatabaseMetaData.getExportedKeys()} call, which can be slow on Oracle.
     */
    default String exportedKeysQuery() {
        return null;
    }

    /**
     * SQL that returns table triggers. Expected columns:
     * {@code schema}, {@code table_name}, {@code name}, {@code timing}, {@code events},
     * {@code enabled}, {@code definition}.
     * Params: {@code (schema, table)}.
     */
    default String tableTriggersQuery() {
        return null;
    }

    /**
     * SQL that returns ALL triggers for a schema (bulk variant).
     * Expected columns: same as {@code tableTriggersQuery()}.
     * Params: {@code (schema)} only.
     * When this returns non-null, the usage catalog uses it instead of iterating per-table.
     */
    default String schemaTriggersQuery() {
        return null;
    }

    /**
     * Bulk variant of {@link #tableConstraintsQuery()} that returns constraints for
     * <b>all</b> tables in a schema in one roundtrip.
     * Expected columns: same as {@code tableConstraintsQuery()} plus
     * {@code table_name} (text) as the first column.
     * Params: {@code (schema)} only.
     * When this returns non-null, {@code describeSchema} uses it instead of iterating per-table.
     */
    default String schemaConstraintsQuery() {
        return null;
    }

    /**
     * SQL that returns a single trigger definition. Expected one column: {@code definition}.
     * Params: {@code (schema, table, trigger)}.
     */
    default String triggerDefinitionQuery() {
        return null;
    }

    /** System schemas that should be hidden from {@code listSchemas} / {@code listTables} by default. */
    List<String> systemSchemas();

    /** Default schema to use when {@code schema} parameter is null/blank and the connection sets no {@code defaultSchema}. */
    default String fallbackSchema(Connection connection) throws SQLException {
        return connection.getSchema();
    }
}
