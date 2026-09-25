package ru.it_spectrum.ai.jdbc.mcp.dialect;

import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Any JDBC database without a dialect of its own, answered through {@link DatabaseMetaData} and
 * portable SQL alone.
 *
 * <p>What that gives up, and what replaces it:
 * <ul>
 *     <li>No catalog SQL: every {@code *Query()} is {@code null}. Tables, columns, keys and indexes
 *         come from {@link DatabaseMetaData}; routines are listed without sources; view, routine and
 *         trigger definitions and sequences are {@code unsupported}.</li>
 *     <li>No plans ({@link PlanCapture#UNSUPPORTED}) and no planner estimates: selectivity and join
 *         cardinality run exact {@code COUNT(*)} queries, row counts come from index statistics or
 *         {@code COUNT(*)}, percentiles are computed client-side from a sorted scan.</li>
 *     <li>No pagination syntax: result size is bounded by {@code Statement#setMaxRows}.</li>
 *     <li>Read-only is best-effort: the guard plus {@link Connection#setReadOnly} where the driver
 *         honours it. Configure the database or URL itself as read-only where possible.</li>
 * </ul>
 *
 * <p>The traits that shape identifiers and schemas — whether the database has schemas, its quote
 * string and case folding, its current catalog — are read from the database's metadata on first
 * use. A database without schemas is presented as one logical schema: its current catalog
 * (MySQL's database) when it has one, otherwise {@value #DEFAULT_LOGICAL_SCHEMA}.
 */
public class GenericDialect implements SqlDialect {

    static final String DEFAULT_LOGICAL_SCHEMA = "PUBLIC";

    private static final String NO_PLANS =
            "Execution plans are not available for generic JDBC connections: JDBC has no portable way to obtain one.";

    private final DataSource dataSource;
    private volatile Traits traits;

    /**
     * @param dataSource where to read the database's traits from; {@code null} for a dialect that
     *                   only answers pool-building questions (see {@link SqlDialect#forKind})
     */
    public GenericDialect(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** What the database's metadata says about identifiers and namespaces. */
    record Traits(boolean schemas, String catalog, String logicalSchema, String quote,
                  boolean storesUpper, boolean storesLower) {

        static Traits of(Connection connection) throws SQLException {
            DatabaseMetaData md = connection.getMetaData();
            boolean schemas = md.supportsSchemasInTableDefinitions();
            String catalog = blankToNull(connection.getCatalog());
            String quote = blankToNull(md.getIdentifierQuoteString());
            return new Traits(schemas, catalog,
                    schemas ? null : (catalog != null ? catalog : DEFAULT_LOGICAL_SCHEMA),
                    quote, md.storesUpperCaseIdentifiers(), md.storesLowerCaseIdentifiers());
        }
    }

    Traits traits() {
        Traits known = traits;
        if (known != null) {
            return known;
        }
        if (dataSource == null) {
            throw new IllegalStateException("GenericDialect built without a data source cannot read database traits");
        }
        synchronized (this) {
            if (traits == null) {
                try (Connection connection = dataSource.getConnection()) {
                    traits = Traits.of(connection);
                } catch (SQLException e) {
                    throw new IllegalStateException("Cannot read database metadata: " + e.getMessage(), e);
                }
            }
            return traits;
        }
    }

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.GENERIC;
    }

    // ---------------- connection ----------------

    @Override
    public boolean readOnlyPoolConnections() {
        return false;
    }

    /** Best-effort: some drivers refuse it on an open connection (SQLite), others ignore it. */
    @Override
    public void prepareReadOnly(Connection connection) {
        try {
            if (!connection.isReadOnly()) {
                connection.setReadOnly(true);
            }
        } catch (SQLException ignored) {
            // the guard remains; a read-only URL or database user is the real protection here
        }
    }

    // ---------------- namespace ----------------

    @Override
    public boolean supportsSchemas() {
        return traits().schemas();
    }

    @Override
    public String logicalSchema() {
        return traits().logicalSchema();
    }

    @Override
    public Connection wrapConnection(Connection connection) {
        Traits t = traits;
        if (t == null) {
            try {
                t = Traits.of(connection);
                traits = t;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read database metadata: " + e.getMessage(), e);
            }
        }
        return t.schemas() ? connection : SchemalessConnections.wrap(connection, t.logicalSchema(), t.catalog());
    }

    @Override
    public String fallbackSchema(Connection connection) throws SQLException {
        return supportsSchemas() ? connection.getSchema() : logicalSchema();
    }

    @Override
    public List<String> systemSchemas() {
        return List.of("information_schema", "INFORMATION_SCHEMA", "pg_catalog", "sys", "SYS");
    }

    /**
     * Preserve mixed-case names reported by metadata: leaving {@code MixedCase} unquoted on a
     * lower-case-folding database would address {@code mixedcase}, possibly a different table.
     * All-upper/all-lower names in the opposite of the stored case remain unquoted so callers may
     * still use the database's normal folding (for example {@code ORDERS} for {@code orders}).
     */
    @Override
    public String quoteIdentifier(String identifier) {
        Traits t = traits();
        boolean allUpper = identifier.equals(identifier.toUpperCase(Locale.ROOT));
        boolean allLower = identifier.equals(identifier.toLowerCase(Locale.ROOT));
        if (t.quote() == null
                || t.storesUpper() && allLower && !allUpper
                || t.storesLower() && allUpper && !allLower) {
            return identifier;
        }
        return t.quote() + identifier + t.quote();
    }

    @Override
    public String qualify(String schema, String table) {
        if (!supportsSchemas()) {
            return quoteIdentifier(table);
        }
        return SqlDialect.super.qualify(schema, table);
    }

    // ---------------- queries ----------------

    /** No portable pagination syntax; callers bound the result with {@code setMaxRows}. */
    @Override
    public String limitQuery(String sql, int limit) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    @Override
    public PlanCapture planCapture() {
        return PlanCapture.UNSUPPORTED;
    }

    @Override
    public boolean plannerRowEstimates() {
        return false;
    }

    @Override
    public String buildExplain(String sql, boolean analyze) {
        throw new UnsupportedFeatureException(NO_PLANS);
    }

    @Override
    public String explainDisplayQuery() {
        return null;
    }

    @Override
    public String buildStructuredExplain(String sql, boolean analyze) {
        throw new UnsupportedFeatureException(NO_PLANS);
    }

    /** Percentiles are computed client-side from a sorted scan, which yields actual values. */
    @Override
    public String histogramPercentileFunction(boolean numeric) {
        return "percentile_disc";
    }

    @Override
    public String histogramQuery(String qualifiedTable, String quotedColumn, String percentileFunction) {
        return null;
    }

    @Override
    public String unusedIndexesUnsupportedReason() {
        return "Generic JDBC exposes no per-index usage counters.";
    }

    // ---------------- catalog: none; DatabaseMetaData or unsupported instead ----------------

    @Override
    public String viewDefinitionQuery() {
        return null;
    }

    @Override
    public String routineSourceQuery() {
        return null;
    }

    @Override
    public String searchObjectsQuery() {
        return null;
    }

    @Override
    public String listSequencesQuery() {
        return null;
    }

    @Override
    public String listRoutinesQuery() {
        return null;
    }

    @Override
    public String tableStatsQuery() {
        return null;
    }

    @Override
    public String indexStatsQuery() {
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
