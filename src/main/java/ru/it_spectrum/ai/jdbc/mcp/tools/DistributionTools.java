package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.DistributionService;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.ColumnDistribution;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.ColumnHistogram;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.ColumnStats;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.JoinCardinality;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.NullRatio;
import ru.it_spectrum.ai.jdbc.mcp.model.distribution.SelectivityEstimate;

import java.sql.SQLException;

/**
 * MCP tools exposing column-level selectivity and distribution analyses: top-N frequent values,
 * percentile histograms, per-column null ratios, and planner-based selectivity / join
 * cardinality estimates. These are the "is my predicate selective?" / "how skewed is this
 * column?" questions the other stats tools deliberately do not answer.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "distribution", havingValue = "true", matchIfMissing = true)
public class DistributionTools {

    private static final Logger log = LoggerFactory.getLogger(DistributionTools.class);

    private final DistributionService distribution;
    private final JsonResponses json;
    private final ToolErrors errors;

    public DistributionTools(DistributionService distribution, JsonResponses json, ToolErrors errors) {
        this.distribution = distribution;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Return basic statistics for a column: total rows, non-null count, distinct count, " +
            "min, max. Cheap one-shot scan for extremes; " +
            "use columnHistogram for percentiles or nullRatio for per-column null shares.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnStats columnStats(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Column name") String column
    ) {
        log.info("Tool call: columnStats (schema={}, table={}, column={})", schema, table, column);
        long start = System.nanoTime();
        try {
            ColumnStats r = distribution.columnStats(schema, table, column);
            ToolLogger.completed(log, "columnStats", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "columnStats", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "columnStats", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return the top-N most frequent values of a column with their share of total rows. " +
            "Detects data skew (e.g. '70% of rows have status=OK'), which makes a plain index nearly useless. " +
            "Executes GROUP BY + COUNT — may be heavy on large tables; keep topN small. Default 20, max 1000.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnDistribution columnDistribution(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Column name") String column,
            @McpToolParam(description = "Number of top values to return (default 20, max 1000)", required = false) Integer topN
    ) {
        log.info("Tool call: columnDistribution (schema={}, table={}, column={})", schema, table, column);
        long start = System.nanoTime();
        try {
            var r = distribution.columnDistribution(schema, table, column, topN);
            ToolLogger.completed(log, "columnDistribution", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "columnDistribution", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "columnDistribution", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return a percentile histogram for a column: min, max, P25/P50/P75/P90/P95/P99 " +
            "plus null counts. Works on numeric (interpolated), date, timestamp and text columns. " +
            "Use instead of columnStats when you need the spread, not just extremes.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnHistogram columnHistogram(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Column name") String column
    ) {
        log.info("Tool call: columnHistogram (schema={}, table={}, column={})", schema, table, column);
        long start = System.nanoTime();
        try {
            var r = distribution.columnHistogram(schema, table, column);
            ToolLogger.completed(log, "columnHistogram", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "columnHistogram", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "columnHistogram", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return the null/non-null ratio for every column of a table in one scan. " +
            "Sorted by descending null_ratio, so sparse columns (candidates for a WHERE col IS NOT NULL " +
            "partial index) appear first. A 'sparse' flag marks columns with null ratio > 50%.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public NullRatio nullRatio(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table
    ) {
        log.info("Tool call: nullRatio (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            var r = distribution.nullRatio(schema, table);
            ToolLogger.completed(log, "nullRatio", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "nullRatio", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "nullRatio", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Estimate how many rows a predicate would return without executing it: " +
            "planner cardinality estimate, baseline (no-predicate) estimate, and selectivity ratio. " +
            "Helps pick the most selective predicate for a composite index. " +
            "Predicate is raw SQL without WHERE " +
            "(e.g. \"status = 'OK' AND created_at > now() - interval '7 days'\"); no ';'.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SelectivityEstimate estimateSelectivity(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Boolean predicate expression — raw SQL, without the WHERE keyword") String predicate
    ) {
        log.info("Tool call: estimateSelectivity (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            var r = distribution.estimateSelectivity(schema, table, predicate);
            ToolLogger.completed(log, "estimateSelectivity", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "estimateSelectivity", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "estimateSelectivity", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Estimate the row count of an equi-join between two tables without executing it: " +
            "planner cardinality estimate, each side's base row estimate, and selectivity vs the Cartesian product. " +
            "Join types: INNER (default), LEFT, RIGHT, FULL.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public JoinCardinality joinCardinality(
            @McpToolParam(description = "From-side schema", required = false) String fromSchema,
            @McpToolParam(description = "From-side table or view name") String fromTable,
            @McpToolParam(description = "From-side join column") String leftColumn,
            @McpToolParam(description = "To-side schema", required = false) String toSchema,
            @McpToolParam(description = "To-side table or view name") String toTable,
            @McpToolParam(description = "To-side join column") String rightColumn,
            @McpToolParam(description = "Join type: INNER (default), LEFT, RIGHT, FULL", required = false) String joinType
    ) {
        log.info("Tool call: joinCardinality ({}.{} <-> {}.{})", fromTable, leftColumn, toTable, rightColumn);
        long start = System.nanoTime();
        try {
            var r = distribution.joinCardinality(fromSchema, fromTable, leftColumn,
                    toSchema, toTable, rightColumn, joinType);
            ToolLogger.completed(log, "joinCardinality", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "joinCardinality", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "joinCardinality", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }
}
