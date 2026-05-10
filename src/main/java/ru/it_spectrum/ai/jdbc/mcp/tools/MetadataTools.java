package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SearchObjectEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SequenceEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP tools exposing database metadata: schemas, tables, columns, indexes, foreign keys,
 * view definitions, routines, sequences, and cross-object search.
 */
@Service
public class MetadataTools {

    private static final Logger log = LoggerFactory.getLogger(MetadataTools.class);

    private final MetadataService metadata;
    private final JsonResponses json;
    private final ToolErrors errors;

    public MetadataTools(MetadataService metadata, JsonResponses json, ToolErrors errors) {
        this.metadata = metadata;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(description = "List all schemas visible to the current user. " +
            "System schemas (pg_catalog, information_schema, SYS, SYSTEM, ...) are excluded by default.")
    public String listSchemas(
            @McpToolParam(description = "Include system schemas. Default false.", required = false) Boolean includeSystem
    ) {
        log.info("Tool call: listSchemas (includeSystem={})", includeSystem);
        try {
            List<String> schemas = metadata.listSchemas(Boolean.TRUE.equals(includeSystem));
            return json.write(schemas);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "List tables and views in a schema. " +
            "If 'schema' is omitted, uses JDBC_DEFAULT_SCHEMA or the current schema. " +
            "'namePattern' supports JDBC wildcards ('%' any, '_' one char). " +
            "'types' filters by table type (defaults to TABLE, VIEW, MATERIALIZED VIEW).")
    public String listTables(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Name pattern with JDBC wildcards, e.g. '%user%' (optional)", required = false) String namePattern,
            @McpToolParam(description = "Comma-separated list of types: TABLE,VIEW,MATERIALIZED VIEW,SYSTEM TABLE,GLOBAL TEMPORARY,LOCAL TEMPORARY,ALIAS,SYNONYM (optional)", required = false) String types
    ) {
        log.info("Tool call: listTables (schema={}, pattern={})", schema, namePattern);
        try {
            String[] typeArr = parseTypes(types);
            List<TableEntry> entries = metadata.listTables(schema, namePattern, typeArr);
            return json.write(entries);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "Describe a table or view completely: columns (name, type, size, nullable, default, remarks), " +
            "primary key, unique constraints, indexes, outgoing foreign keys, and tables that reference this one. " +
            "Returned in one JSON document so the LLM has everything it needs to write a correct query.")
    public String describeTable(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table
    ) {
        log.info("Tool call: describeTable (schema={}, table={})", schema, table);
        try {
            TableDescription info = metadata.describeTable(schema, table);
            return json.write(info);
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Get a trigger definition/body for one table trigger.")
    public String getTriggerDefinition(
            @McpToolParam(description = "Schema name (optional — defaults to current/default schema)", required = false) String schema,
            @McpToolParam(description = "Table or view name") String table,
            @McpToolParam(description = "Trigger name") String trigger
    ) {
        log.info("Tool call: getTriggerDefinition (schema={}, table={}, trigger={})", schema, table, trigger);
        try {
            String def = metadata.triggerDefinition(schema, table, trigger);
            return def == null || def.isBlank() ? errors.notFound("trigger", trigger) : def;
        } catch (SQLException e) {
            return errors.sql(e);
        } catch (IllegalArgumentException e) {
            return errors.argument(e);
        }
    }

    @McpTool(description = "Get the SQL definition of a view or materialized view.")
    public String getViewDefinition(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "View name") String name
    ) {
        log.info("Tool call: getViewDefinition (schema={}, name={})", schema, name);
        try {
            String def = metadata.viewDefinition(schema, name);
            return def == null ? errors.notFound("view", name) : def;
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "List functions / procedures / packages in a schema. " +
            "For Oracle, packages are listed once (use getRoutineDefinition to get the source of a specific package body).")
    public String listRoutines(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Name pattern (optional, e.g. '%calculate%')", required = false) String namePattern
    ) {
        log.info("Tool call: listRoutines (schema={}, pattern={})", schema, namePattern);
        try {
            List<RoutineEntry> r = metadata.listRoutines(schema, namePattern);
            return json.write(r);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "Get the source code of a function / procedure / package (body, where applicable). " +
            "On Oracle the result contains the concatenated source lines from ALL_SOURCE.")
    public String getRoutineDefinition(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema,
            @McpToolParam(description = "Routine name") String name
    ) {
        log.info("Tool call: getRoutineDefinition (schema={}, name={})", schema, name);
        try {
            String source = metadata.routineSource(schema, name);
            return source == null || source.isEmpty() ? errors.notFound("routine", name) : source;
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "List sequences in a schema (or in all schemas if schema is omitted).")
    public String listSequences(
            @McpToolParam(description = "Schema name (optional)", required = false) String schema
    ) {
        log.info("Tool call: listSequences (schema={})", schema);
        try {
            List<SequenceEntry> r = metadata.listSequences(schema);
            return json.write(r);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    @McpTool(description = "Search all non-system database objects by name pattern. " +
            "Case-insensitive substring match. Returns up to 200 matches across tables, views, " +
            "materialized views, routines, packages, sequences and synonyms. " +
            "Useful when the LLM knows a partial name and needs to locate the object and its schema.")
    public String searchObjects(
            @McpToolParam(description = "Name pattern — plain substring (auto-wrapped in %..%) or explicit pattern with % / _") String namePattern
    ) {
        log.info("Tool call: searchObjects (pattern={})", namePattern);
        try {
            List<SearchObjectEntry> r = metadata.searchObjects(namePattern);
            return json.write(r);
        } catch (SQLException e) {
            return errors.sql(e);
        }
    }

    // ---------------- helpers ----------------

    private String[] parseTypes(String types) {
        if (types == null || types.isBlank()) return null;
        String[] parts = types.split(",");
        List<String> cleaned = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) cleaned.add(t);
        }
        return cleaned.isEmpty() ? null : cleaned.toArray(new String[0]);
    }
}
