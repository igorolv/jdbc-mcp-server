package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;

import java.sql.SQLException;
import java.util.Map;

/**
 * MCP tools exposing column-level selectivity and distribution analyses: top-N frequent values,
 * percentile histograms, per-column null ratios, and planner-based selectivity / join
 * cardinality estimates. These are the "is my predicate selective?" / "how skewed is this
 * column?" questions the other stats tools deliberately do not answer.
 */
@Service
public class DistributionTools {

    private final DistributionService distribution;

    public DistributionTools(DistributionService distribution) {
        this.distribution = distribution;
    }

    @McpTool(description = "Return the top-N most frequent values of a column together with their " +
            "share of the total row count. Helps detect data skew — e.g. '70 % of rows have " +
            "status=OK' — which makes a plain index on that column nearly useless without another " +
            "predicate in the WHERE clause. Executes GROUP BY + COUNT — may be heavy on very large " +
            "tables; use a limited topN. Default topN=20, max 1000.")
    public String columnDistribution(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Column name") String column,
            @McpToolParam(description = "Number of top values to return (default 20, max 1000)", required = false) Integer topN
    ) {
        try {
            Map<String, Object> r = distribution.columnDistribution(schema, table, column, topN);
            return MetadataTools.JsonWriter.write(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Return a percentile histogram for a column: min, max, P25, P50, P75, " +
            "P90, P95, P99 plus null counts. Uses standard SQL:2003 WITHIN GROUP aggregates — " +
            "percentile_cont for numeric types (interpolated), percentile_disc for everything else " +
            "(date, timestamp, text) so it works on both engines. Use this instead of columnStats " +
            "when you need to know the spread, not just the extremes.")
    public String columnHistogram(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Column name") String column
    ) {
        try {
            Map<String, Object> r = distribution.columnHistogram(schema, table, column);
            return MetadataTools.JsonWriter.write(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Return the null / non-null ratio for every column of a table in a single " +
            "scan. Columns are sorted by descending null_ratio so sparse columns — the ones most " +
            "likely to benefit from a WHERE col IS NOT NULL partial index — appear first. " +
            "A 'sparse' flag is set on columns whose null ratio is > 50 %.")
    public String nullRatio(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table
    ) {
        try {
            Map<String, Object> r = distribution.nullRatio(schema, table);
            return MetadataTools.JsonWriter.write(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Estimate how many rows a predicate would return, without executing the " +
            "query. Runs EXPLAIN (FORMAT JSON) on PostgreSQL / EXPLAIN PLAN on Oracle against " +
            "'SELECT 1 FROM <table> WHERE <predicate>' and reports the planner's cardinality " +
            "estimate, together with a baseline (no predicate) estimate and the selectivity ratio. " +
            "Useful for choosing the most selective predicate to put first in a composite index. " +
            "Predicate syntax is raw SQL (e.g. \"status = 'OK' AND created_at > now() - interval '7 days'\"). " +
            "A ';' in the predicate is rejected to prevent multi-statement injection.")
    public String estimateSelectivity(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Boolean predicate expression — raw SQL, without the WHERE keyword") String predicate
    ) {
        try {
            Map<String, Object> r = distribution.estimateSelectivity(schema, table, predicate);
            return MetadataTools.JsonWriter.write(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }

    @McpTool(description = "Estimate the row count of an equi-join between two tables without " +
            "executing the query. Builds 'SELECT 1 FROM <left> JOIN <right> ON left.col = right.col' " +
            "and reports the planner's cardinality estimate plus each side's base row estimate " +
            "and the selectivity versus the Cartesian product. Supported join types: INNER " +
            "(default), LEFT, RIGHT, FULL.")
    public String joinCardinality(
            @McpToolParam(description = "Left schema name (optional — defaults to current/default schema)", required = false) String leftSchema,
            @McpToolParam(description = "Left table or view name") String leftTable,
            @McpToolParam(description = "Left join column") String leftColumn,
            @McpToolParam(description = "Right schema name (optional — defaults to current/default schema)", required = false) String rightSchema,
            @McpToolParam(description = "Right table or view name") String rightTable,
            @McpToolParam(description = "Right join column") String rightColumn,
            @McpToolParam(description = "Join type: INNER (default), LEFT, RIGHT, FULL", required = false) String joinType
    ) {
        try {
            Map<String, Object> r = distribution.joinCardinality(leftSchema, leftTable, leftColumn,
                    rightSchema, rightTable, rightColumn, joinType);
            return MetadataTools.JsonWriter.write(r);
        } catch (SQLException e) {
            return "SQL error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Invalid argument: " + e.getMessage();
        }
    }
}
