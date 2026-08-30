package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.FkIndexCoverage;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.IndexStats;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.RedundantIndexes;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.TableStats;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.UnusedIndexes;

import java.sql.SQLException;

/**
 * MCP tools exposing object-level statistics useful for query optimisation:
 * row counts, storage sizes, index usage/cardinality, unused / redundant indexes,
 * and missing FK indexes. These answer the "how big is this?" / "is this indexed well?"
 * questions that the LLM otherwise has to guess.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "stats", havingValue = "true", matchIfMissing = true)
public class StatsTools {

    private static final Logger log = LoggerFactory.getLogger(StatsTools.class);

    private final ConnectionRegistry connections;
    private final JsonResponses json;
    private final ToolErrors errors;

    public StatsTools(ConnectionRegistry connections, JsonResponses json, ToolErrors errors) {
        this.connections = connections;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Return estimated rows, storage sizes, dead-tuple ratio, maintenance timestamps and scan " +
            "counters for a table. Available fields depend on the engine and privileges.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TableStats tableStats(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: tableStats (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            TableStats info = ctx.stats().tableStats(schema, table);
            ToolLogger.completed(log, "tableStats", start);
            return info;
        } catch (SQLException e) {
            ToolLogger.failed(log, "tableStats", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "tableStats", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Return index columns, size, uniqueness/primary flags and available usage/cardinality " +
            "counters for a table or schema.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public IndexStats indexStats(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Omit to scan the schema.", required = false) String table,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: indexStats (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            IndexStats r = ctx.stats().indexStats(schema, table);
            ToolLogger.completed(log, "indexStats", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "indexStats", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "Return non-PK/non-unique indexes with zero scans. Counters are cumulative since reset and " +
            "meaningful only after a representative traffic cycle; not supported by every engine.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public UnusedIndexes unusedIndexes(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Minimum size in bytes; omit tiny indexes.", required = false) Long minSizeBytes,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: unusedIndexes (schema={})", schema);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.stats().unusedIndexes(schema, minSizeBytes);
            ToolLogger.completed(log, "unusedIndexes", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "unusedIndexes", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "Return non-unique indexes whose leading columns are a strict prefix of another same-type " +
            "index on the same table.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public RedundantIndexes redundantIndexes(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Omit to scan the schema.", required = false) String table,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: redundantIndexes (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.stats().redundantIndexes(schema, table);
            ToolLogger.completed(log, "redundantIndexes", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "redundantIndexes", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "Return child-side foreign keys not covered by an index starting with the FK columns in " +
            "order, including suggested index columns.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FkIndexCoverage fkIndexCoverage(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "Omit to scan the schema.", required = false) String table,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: fkIndexCoverage (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            var result = ctx.stats().fkIndexCoverage(schema, table);
            ToolLogger.completed(log, "fkIndexCoverage", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "fkIndexCoverage", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }
}
