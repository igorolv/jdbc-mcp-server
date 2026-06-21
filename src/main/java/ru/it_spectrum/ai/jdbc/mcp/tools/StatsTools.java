package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.FkIndexCoverage;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.IndexStats;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.RedundantIndexes;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.TableStats;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
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

    private final StatsService stats;
    private final JsonResponses json;
    private final ToolErrors errors;

    public StatsTools(StatsService stats, JsonResponses json, ToolErrors errors) {
        this.stats = stats;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "Return per-table storage and activity statistics: estimated row count, " +
            "total / heap / index / toast size in bytes, dead tuple ratio, last vacuum / analyze " +
            "timestamps, sequential vs index scan counters. The exact column set depends on the engine, " +
            "and some fields are skipped when the account lacks the needed privileges.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TableStats tableStats(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table name") String table
    ) {
        log.info("Tool call: tableStats (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            TableStats info = stats.tableStats(schema, table);
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
            description = "Return per-index statistics for a table or whole schema: size, scan counters, " +
            "column list, uniqueness, primary flag, and engine-specific signals such as scan/read counts " +
            "and index cardinality. Use it before deciding whether to add/drop an index.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public IndexStats indexStats(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table; omit to scan whole schema", required = false) String table
    ) {
        log.info("Tool call: indexStats (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            IndexStats r = stats.indexStats(schema, table);
            ToolLogger.completed(log, "indexStats", start);
            return r;
        } catch (SQLException e) {
            ToolLogger.failed(log, "indexStats", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "List indexes that have zero recorded scans — candidates for removal. " +
            "Excludes primary key / unique indexes (dropping them would break the constraint). " +
            "Uses cumulative scan counters since the last stats reset; not supported on every engine " +
            "(returns a diagnostic note where unavailable). Caveat: 'never-scanned' is only a strong hint " +
            "after a full business cycle of traffic since the counters were reset.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public UnusedIndexes unusedIndexes(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Minimum index size in bytes to report; filters out tiny indexes", required = false) Long minSizeBytes
    ) {
        log.info("Tool call: unusedIndexes (schema={})", schema);
        long start = System.nanoTime();
        try {
            var result = stats.unusedIndexes(schema, minSizeBytes);
            ToolLogger.completed(log, "unusedIndexes", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "unusedIndexes", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "List indexes whose leading-column list is a strict prefix of another index on the same table — " +
            "the shorter index is redundant and a candidate for removal. " +
            "Only non-unique indexes are reported (removing a UNIQUE index loses the constraint). " +
            "Index types must match (we do not claim a GIN index shadows a BTREE).",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public RedundantIndexes redundantIndexes(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table; omit to scan whole schema", required = false) String table
    ) {
        log.info("Tool call: redundantIndexes (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            var result = stats.redundantIndexes(schema, table);
            ToolLogger.completed(log, "redundantIndexes", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "redundantIndexes", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "List foreign keys on the child side that lack a supporting index. " +
            "A FK is considered covered if some index starts with exactly the FK columns in order. " +
            "Missing FK indexes are a classic cause of slow DELETE/UPDATE cascades and slow joins. " +
            "If 'table' is omitted, every table in the schema is scanned. " +
            "Each entry includes a suggested_index_columns list that you can feed directly into a CREATE INDEX.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public FkIndexCoverage fkIndexCoverage(
            @McpToolParam(description = "Schema", required = false) String schema,
            @McpToolParam(description = "Table; omit to scan whole schema", required = false) String table
    ) {
        log.info("Tool call: fkIndexCoverage (schema={}, table={})", schema, table);
        long start = System.nanoTime();
        try {
            var result = stats.fkIndexCoverage(schema, table);
            ToolLogger.completed(log, "fkIndexCoverage", start);
            return result;
        } catch (SQLException e) {
            ToolLogger.failed(log, "fkIndexCoverage", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }
}
