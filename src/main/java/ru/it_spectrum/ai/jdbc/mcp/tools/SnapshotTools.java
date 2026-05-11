package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaSnapshotCache;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.snapshot.InvalidateSnapshotResult;
import ru.it_spectrum.ai.jdbc.mcp.model.snapshot.RefreshSchemaSnapshotResult;

import java.sql.SQLException;
import java.util.List;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

/**
 * MCP tools for inspecting and refreshing the in-memory metadata snapshot cache.
 *
 * <p>The cache holds structural metadata (columns, keys, indexes, FKs, constraints, triggers)
 * with a TTL configured by {@code JDBC_METADATA_CACHE_TTL_SECONDS} (default 300). It speeds up
 * {@code schemaOverview}, {@code tableContext}, {@code findJoinPaths}, {@code schemaLint},
 * {@code schemaGraph}, {@code queryContext} and repeated {@code describeTable} calls.
 */
@Service
public class SnapshotTools {

    private static final Logger log = LoggerFactory.getLogger(SnapshotTools.class);

    private final MetadataService metadata;
    private final SchemaSnapshotCache cache;
    private final JsonResponses json;
    private final ToolErrors errors;

    public SnapshotTools(MetadataService metadata, SchemaSnapshotCache cache, JsonResponses json, ToolErrors errors) {
        this.metadata = metadata;
        this.cache = cache;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(description = "Return meta-information about the in-memory metadata snapshot cache: " +
            "TTL, hit/miss counters, per-schema cached table names, and list-cache entries. " +
            "Does NOT return the full table descriptions — use schemaOverview for that. " +
            "Pass schema to filter to a single schema.")
    public String getSchemaSnapshot(
            @McpToolParam(description = "Schema name (optional — omit to see all cached schemas)", required = false) String schema
    ) {
        log.info("Tool call: getSchemaSnapshot (schema={})", schema);
        return json.write(cache.snapshotInfo(schema));
    }

    @McpTool(description = "Invalidate cached metadata and (by default) eagerly rebuild it. " +
            "If 'table' is provided, only that table is refreshed. " +
            "If only 'schema' is provided, all tables in the schema are listed and described. " +
            "If neither is provided, the entire cache is cleared (and not pre-warmed). " +
            "Returns counts of refreshed entries and durations.")
    public String refreshSchemaSnapshot(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema when 'table' is set)", required = false) String schema,
            @McpToolParam(description = "Single table to refresh (optional)", required = false) String table,
            @McpToolParam(description = "Maximum tables to warm when refreshing a whole schema. Default 300.", required = false) Integer maxTables
    ) {
        log.info("Tool call: refreshSchemaSnapshot (schema={}, table={})", schema, table);
        RefreshSchemaSnapshotResult result;
        try {
            if (table != null && !table.isBlank()) {
                cache.invalidateTable(schema, table);
                TableDescription described = metadata.describeTable(schema, table);
                result = refreshResult(schema, table, described.schema(), described.name(),
                        null, null);
            } else if (schema != null && !schema.isBlank()) {
                cache.invalidateSchema(schema);
                int limit = maxTables == null ? 300 : Math.clamp(maxTables, 1, 5000);
                List<TableEntry> listed = metadata.listTables(schema, "%",
                        new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"});
                int count = 0;
                int errors = 0;
                for (TableEntry row : listed) {
                    if (count >= limit) break;
                    String name = row.name();
                    String sch = row.schema();
                    if (name == null) continue;
                    try {
                        metadata.describeTable(sch, name);
                        count++;
                    } catch (SQLException e) {
                        errors++;
                    }
                }
                result = refreshResult(schema, table, null, null,
                        limit, listed.size() > count);
            } else {
                cache.invalidateAll();
                result = refreshResult(schema, table, null, null,
                        null, null);
            }
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
        return json.write(result);
    }

    @McpTool(description = "Invalidate cached metadata without re-warming. " +
            "Pass 'table' to drop one table, 'schema' to drop all entries for a schema, " +
            "or omit both to clear the entire cache.")
    public String invalidateSnapshot(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Single table to invalidate (optional)", required = false) String table
    ) {
        log.info("Tool call: invalidateSnapshot (schema={}, table={})", schema, table);
        InvalidateSnapshotResult result;
        if (table != null && !table.isBlank()) {
            cache.invalidateTable(schema, table);
            result = new InvalidateSnapshotResult("table", schema, table);
        } else if (schema != null && !schema.isBlank()) {
            cache.invalidateSchema(schema);
            result = new InvalidateSnapshotResult("schema", schema, null);
        } else {
            cache.invalidateAll();
            result = new InvalidateSnapshotResult("all", null, null);
        }
        return json.write(result);
    }

    private RefreshSchemaSnapshotResult refreshResult(
            String schema,
            String table,
            String tableSchema,
            String tableName,
            Integer limit,
            Boolean truncated
    ) {
        return new RefreshSchemaSnapshotResult(
                schema,
                table,
                tableSchema,
                tableName,
                limit,
                truncated,
                cache.ttlMs() / 1000L,
                cache.enabled());
    }
}
