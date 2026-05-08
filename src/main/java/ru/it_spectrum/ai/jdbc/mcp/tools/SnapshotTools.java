package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.SchemaSnapshotCache;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final MetadataService metadata;
    private final SchemaSnapshotCache cache;

    public SnapshotTools(MetadataService metadata, SchemaSnapshotCache cache) {
        this.metadata = metadata;
        this.cache = cache;
    }

    @McpTool(description = "Return meta-information about the in-memory metadata snapshot cache: " +
            "TTL, hit/miss counters, per-schema cached table names, and list-cache entries. " +
            "Does NOT return the full table descriptions — use schemaOverview for that. " +
            "Pass schema to filter to a single schema.")
    public String getSchemaSnapshot(
            @McpToolParam(description = "Schema name (optional — omit to see all cached schemas)", required = false) String schema
    ) {
        return JsonWriter.write(cache.snapshotInfo(schema));
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
        long startedAt = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", schema);
        result.put("table", table);
        try {
            if (table != null && !table.isBlank()) {
                cache.invalidateTable(schema, table);
                TableDescription described = metadata.describeTable(schema, table);
                result.put("refreshedTables", 1);
                result.put("tableSchema", described.schema());
                result.put("tableName", described.name());
            } else if (schema != null && !schema.isBlank()) {
                cache.invalidateSchema(schema);
                int limit = maxTables == null ? 300 : Math.max(1, Math.min(maxTables, 5000));
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
                result.put("listedTables", listed.size());
                result.put("refreshedTables", count);
                result.put("errors", errors);
                result.put("limit", limit);
                result.put("truncated", listed.size() > count);
            } else {
                cache.invalidateAll();
                result.put("refreshedTables", 0);
                result.put("clearedAll", true);
            }
        } catch (SQLException e) {
            return ToolErrors.sql(e);
        } catch (IllegalArgumentException e) {
            return ToolErrors.argument(e);
        }
        result.put("durationMs", System.currentTimeMillis() - startedAt);
        result.put("ttlSeconds", cache.ttlMs() / 1000L);
        result.put("enabled", cache.enabled());
        return JsonWriter.write(result);
    }

    @McpTool(description = "Invalidate cached metadata without re-warming. " +
            "Pass 'table' to drop one table, 'schema' to drop all entries for a schema, " +
            "or omit both to clear the entire cache.")
    public String invalidateSnapshot(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Single table to invalidate (optional)", required = false) String table
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (table != null && !table.isBlank()) {
            cache.invalidateTable(schema, table);
            result.put("invalidated", "table");
            result.put("schema", schema);
            result.put("table", table);
        } else if (schema != null && !schema.isBlank()) {
            cache.invalidateSchema(schema);
            result.put("invalidated", "schema");
            result.put("schema", schema);
        } else {
            cache.invalidateAll();
            result.put("invalidated", "all");
        }
        return JsonWriter.write(result);
    }
}
