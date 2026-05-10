package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StatsService;
import ru.it_spectrum.ai.jdbc.mcp.model.stats.TableStats;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.sql.SQLException;

/**
 * MCP tools exposing object-level statistics useful for query optimisation:
 * row counts, storage sizes, index usage/cardinality, unused / redundant indexes,
 * and missing FK indexes. These answer the "how big is this?" / "is this indexed well?"
 * questions that the LLM otherwise has to guess.
 */
@Service
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

    @McpTool(description = "Return per-table storage and activity statistics: estimated row count, " +
            "total / heap / indexes / toast size in bytes, dead tuple ratio (PG), last vacuum / analyze " +
            "timestamps, sequential vs index scan counters. " +
            "On Oracle also attempts a DBA_SEGMENTS lookup for on-disk size (silently skipped if the user " +
            "lacks that privilege). The exact column set depends on the database engine.")
    public String tableStats(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table name") String table
    ) {
        log.info("Tool call: tableStats (schema={}, table={})", schema, table);
        try {
            TableStats info = stats.tableStats(schema, table);
            return json.write(info);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Return per-index statistics for a table or whole schema: size, scan counters, " +
            "column list, uniqueness, primary flag, and engine-specific signals " +
            "(PostgreSQL: idx_scans / idx_tup_read from pg_stat_user_indexes; " +
            "Oracle: distinct_keys, clustering_factor, blevel, leaf_blocks, last_analyzed). " +
            "Use it before deciding whether to add/drop an index.")
    public String indexStats(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Table name (optional — omit to scan every table in the schema)", required = false) String table
    ) {
        log.info("Tool call: indexStats (schema={}, table={})", schema, table);
        try {
            QueryResult r = stats.indexStats(schema, table);
            return json.write(r);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "List indexes that have zero recorded scans — candidates for removal. " +
            "Excludes primary key / unique indexes (dropping them would break the constraint). " +
            "PostgreSQL: uses pg_stat_user_indexes.idx_scan (cumulative since the last stats reset). " +
            "Oracle: not directly supported — returns a diagnostic note pointing to DBA_INDEX_USAGE / V$OBJECT_USAGE. " +
            "Caveat: a 'never-scanned' index is only a strong hint if the database has observed a full " +
            "business cycle of traffic since the counters were reset.")
    public String unusedIndexes(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Minimum index size in bytes to report (optional — filters out tiny indexes)", required = false) Long minSizeBytes
    ) {
        log.info("Tool call: unusedIndexes (schema={})", schema);
        try {
            var result = stats.unusedIndexes(schema, minSizeBytes);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "List indexes whose leading-column list is a strict prefix of another index on the same table — " +
            "the shorter index is redundant and a candidate for removal. " +
            "Only non-unique indexes are reported (removing a UNIQUE index loses the constraint). " +
            "Index types must match (we do not claim a GIN index shadows a BTREE).")
    public String redundantIndexes(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Table name (optional — omit to scan every table in the schema)", required = false) String table
    ) {
        log.info("Tool call: redundantIndexes (schema={}, table={})", schema, table);
        try {
            var result = stats.redundantIndexes(schema, table);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "List foreign keys on the child side that lack a supporting index. " +
            "A FK is considered covered if some index starts with exactly the FK columns in order. " +
            "Missing FK indexes are a classic cause of slow DELETE/UPDATE cascades and slow joins. " +
            "If 'table' is omitted, every table in the schema is scanned. " +
            "Each entry includes a suggested_index_columns list that you can feed directly into a CREATE INDEX.")
    public String fkIndexCoverage(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Table name (optional — omit to scan every table in the schema)", required = false) String table
    ) {
        log.info("Tool call: fkIndexCoverage (schema={}, table={})", schema, table);
        try {
            var result = stats.fkIndexCoverage(schema, table);
            return json.write(result);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }
}
