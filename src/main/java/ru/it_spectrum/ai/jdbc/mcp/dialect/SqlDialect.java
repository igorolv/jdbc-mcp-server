package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Database-specific SQL generation and fix-ups. Each concrete implementation knows how to:
 * <ul>
 *     <li>build an {@code EXPLAIN} statement that is read-only (no side effects);</li>
 *     <li>fetch view / routine / sequence definitions via engine-specific catalogs;</li>
 *     <li>apply pagination to a user query (for {@code sampleRows} and output truncation);</li>
 *     <li>prepare a connection for read-only usage (e.g. {@code SET TRANSACTION READ ONLY} on Oracle).</li>
 * </ul>
 */
public interface SqlDialect {

    DatabaseKind kind();

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
     *                (note: this executes the query!). Oracle ignores this flag and always
     *                returns a static plan via DBMS_XPLAN.
     */
    String buildExplain(String sql, boolean analyze);

    /**
     * Some dialects (Oracle) execute EXPLAIN in two steps: the {@code buildExplain()}
     * statement populates a plan table, then this statement reads the result. For PostgreSQL
     * this returns {@code null} — the EXPLAIN itself produces the rows.
     */
    String explainDisplayQuery();

    /** SQL that returns the definition of a view ({@code schema}, {@code name}). */
    String viewDefinitionQuery();

    /** SQL that returns the source of a routine ({@code schema}, {@code name}). */
    String routineSourceQuery();

    /** SQL that returns {@code (schema, name, type, owner)} rows for a case-insensitive name pattern. */
    String searchObjectsQuery();

    /** SQL that returns {@code (schema, name, min_value, max_value, increment, last_value)} for sequences. */
    String listSequencesQuery();

    /** SQL that returns {@code (schema, name, kind, language)} for routines (functions / procedures). */
    String listRoutinesQuery();

    /** System schemas that should be hidden from {@code listSchemas} / {@code listTables} by default. */
    List<String> systemSchemas();

    /** Default schema to use when {@code schema} parameter is null/blank and {@code JDBC_DEFAULT_SCHEMA} is unset. */
    default String fallbackSchema(Connection connection) throws SQLException {
        return connection.getSchema();
    }

    /**
     * Post-process a row from metadata queries to provide a unified shape if needed.
     * Default implementation is a no-op.
     */
    default Map<String, Object> normalizeMetadataRow(Map<String, Object> row) {
        return row;
    }
}
