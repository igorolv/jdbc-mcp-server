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
            description = "Return total, non-null and distinct row counts plus min/max for one column.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnStats columnStats(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String column,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
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
            description = "Return top-N values with counts and shares of total rows. Runs GROUP BY + COUNT and " +
            "may be expensive on large tables.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnDistribution columnDistribution(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String column,
            @McpToolParam(description = "Top values to return (default 20, max 1000).", required = false) Integer topN,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
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
            description = "Return min/max, P25/P50/P75/P90/P95/P99 and null counts for a numeric, date, " +
            "timestamp or text column.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ColumnHistogram columnHistogram(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String column,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
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
            description = "Return null/non-null counts and ratios for every column, sorted by descending null " +
            "ratio; 'sparse' marks ratios above 50%.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public NullRatio nullRatio(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
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
            description = "Return planner-estimated rows, the unfiltered baseline and predicate selectivity " +
            "without executing the query.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SelectivityEstimate estimateSelectivity(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "Raw boolean SQL without WHERE or ';'.") String predicate,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
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
            description = "Return planner-estimated equi-join rows, per-side base estimates and selectivity versus " +
            "the Cartesian product without executing the join.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public JoinCardinality joinCardinality(
            @McpToolParam(description = "", required = false) String fromSchema,
            @McpToolParam(description = "") String fromTable,
            @McpToolParam(description = "Column in fromTable.") String leftColumn,
            @McpToolParam(description = "", required = false) String toSchema,
            @McpToolParam(description = "") String toTable,
            @McpToolParam(description = "Column in toTable.") String rightColumn,
            @McpToolParam(description = "Join type: INNER (default), LEFT, RIGHT, FULL", required = false) String joinType,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
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
