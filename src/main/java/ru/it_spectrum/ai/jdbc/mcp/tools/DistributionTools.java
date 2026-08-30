package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
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

    private final ConnectionRegistry connections;
    private final JsonResponses json;
    private final ToolErrors errors;

    public DistributionTools(ConnectionRegistry connections, JsonResponses json, ToolErrors errors) {
        this.connections = connections;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Measure basic extremes and cardinality for one known column: total/non-null rows, " +
            "distinct count and min/max. Use columnDistribution for frequent values or columnHistogram for " +
            "percentiles.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnStats columnStats(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String column
    ) {
        log.info("Tool call: columnStats (schema={}, table={}, column={})", schema, table, column);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            ColumnStats r = ctx.distribution().columnStats(schema, table, column);
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
            description = "Measure value frequency and skew for one known column by returning top-N values, counts " +
            "and row shares. Use columnStats for only cardinality/extremes; runs GROUP BY + COUNT and may be " +
            "expensive on large tables.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnDistribution columnDistribution(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String column,
            @McpToolParam(description = "Top values to return (default 20, max 1000).", required = false) Integer topN
    ) {
        log.info("Tool call: columnDistribution (schema={}, table={}, column={})", schema, table, column);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var r = ctx.distribution().columnDistribution(schema, table, column, topN);
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
            description = "Measure percentile distribution for one orderable numeric, date, timestamp or text " +
            "column: min/max, P25/P50/P75/P90/P95/P99 and null counts. Use columnDistribution for top frequent " +
            "values instead.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnHistogram columnHistogram(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String column
    ) {
        log.info("Tool call: columnHistogram (schema={}, table={}, column={})", schema, table, column);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var r = ctx.distribution().columnHistogram(schema, table, column);
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
            description = "Find sparse or mostly-null fields across every column of one table in a single scan. " +
            "Returns null/non-null counts and ratios sorted by sparsity; use describeTable when only declared " +
            "nullability is needed.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public NullRatio nullRatio(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table
    ) {
        log.info("Tool call: nullRatio (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var r = ctx.distribution().nullRatio(schema, table);
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
            description = "Estimate how selective one proposed table predicate is without executing the query. " +
            "Returns planner-estimated rows, the unfiltered baseline and their ratio; use when evaluating filters " +
            "or composite-index column order.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SelectivityEstimate estimateSelectivity(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "Raw boolean SQL without WHERE or ';'.") String predicate
    ) {
        log.info("Tool call: estimateSelectivity (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var r = ctx.distribution().estimateSelectivity(schema, table, predicate);
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
            description = "Estimate the output size and selectivity of a proposed equi-join between two known " +
            "tables without executing it. Returns planner estimates for both sides and versus the Cartesian product; " +
            "use findJoinPaths when the join route itself is unknown.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public JoinCardinality joinCardinality(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String fromSchema,
            @McpToolParam(description = "") String fromTable,
            @McpToolParam(description = "Column in fromTable.") String leftColumn,
            @McpToolParam(description = "", required = false) String toSchema,
            @McpToolParam(description = "") String toTable,
            @McpToolParam(description = "Column in toTable.") String rightColumn,
            @McpToolParam(description = "Join type: INNER (default), LEFT, RIGHT, FULL", required = false) String joinType
    ) {
        log.info("Tool call: joinCardinality ({}.{} <-> {}.{})", fromTable, leftColumn, toTable, rightColumn);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var r = ctx.distribution().joinCardinality(fromSchema, fromTable, leftColumn,
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
