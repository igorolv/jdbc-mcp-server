package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ListSchemasResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ListTablesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ListRoutinesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ListSequencesResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SearchObjectsResult;
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
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "metadata", havingValue = "true", matchIfMissing = true)
public class MetadataTools {

    private static final Logger log = LoggerFactory.getLogger(MetadataTools.class);

    private final ConnectionRegistry connections;
    private final JsonResponses json;
    private final ToolErrors errors;

    public MetadataTools(ConnectionRegistry connections, JsonResponses json, ToolErrors errors) {
        this.connections = connections;
        this.json = json;
        this.errors = errors;
    }

    @McpTool(
            description = "List schemas visible to the current user.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListSchemasResult listSchemas(
            @McpToolParam(description = "Include system schemas. Default false.", required = false) Boolean includeSystem,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listSchemas (includeSystem={})", includeSystem);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            List<String> schemas = ctx.metadata().listSchemas(Boolean.TRUE.equals(includeSystem));
            ToolLogger.completed(log, "listSchemas", start);
            return new ListSchemasResult(schemas);
        } catch (SQLException e) {
            ToolLogger.failed(log, "listSchemas", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "List tables and views in a schema.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListTablesResult listTables(
            @McpToolParam(description = "Omit to use JDBC_DEFAULT_SCHEMA or the current schema.", required = false) String schema,
            @McpToolParam(description = "JDBC pattern ('%' any, '_' one character).", required = false) String namePattern,
            @McpToolParam(description = "JDBC table types CSV (default TABLE,VIEW,MATERIALIZED VIEW): TABLE,VIEW,MATERIALIZED VIEW,SYSTEM TABLE,GLOBAL TEMPORARY,LOCAL TEMPORARY,ALIAS,SYNONYM", required = false) String types,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listTables (schema={}, pattern={})", schema, namePattern);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String[] typeArr = parseTypes(types);
            List<TableEntry> entries = ctx.metadata().listTables(schema, namePattern, typeArr);
            ToolLogger.completed(log, "listTables", start);
            return new ListTablesResult(entries);
        } catch (SQLException e) {
            ToolLogger.failed(log, "listTables", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "Return columns, primary/unique keys, indexes, outgoing foreign keys, incoming references, " +
            "constraints, allowed CHECK values and triggers for a table or view.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TableDescription describeTable(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: describeTable (schema={}, table={})", schema, table);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            TableDescription info = ctx.metadata().describeTable(schema, table);
            ToolLogger.completed(log, "describeTable", start);
            return info;
        } catch (SQLException e) {
            ToolLogger.failed(log, "describeTable", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "describeTable", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Get a trigger definition/body for one table trigger.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String getTriggerDefinition(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String trigger,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: getTriggerDefinition (schema={}, table={}, trigger={})", schema, table, trigger);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String def = ctx.metadata().triggerDefinition(schema, table, trigger);
            ToolLogger.completed(log, "getTriggerDefinition", start);
            if (def == null || def.isBlank()) {
                throw errors.notFoundException("trigger", trigger);
            }
            return def;
        } catch (SQLException e) {
            ToolLogger.failed(log, "getTriggerDefinition", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (IllegalArgumentException e) {
            ToolLogger.failed(log, "getTriggerDefinition", start, e.getMessage());
            throw errors.argumentException(e);
        }
    }

    @McpTool(
            description = "Get the SQL definition of a view or materialized view.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String getViewDefinition(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String name,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: getViewDefinition (schema={}, name={})", schema, name);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String def = ctx.metadata().viewDefinition(schema, name);
            ToolLogger.completed(log, "getViewDefinition", start);
            if (def == null) {
                throw errors.notFoundException("view", name);
            }
            return def;
        } catch (SQLException e) {
            ToolLogger.failed(log, "getViewDefinition", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "List functions, procedures and packages in a schema; packages are listed once.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListRoutinesResult listRoutines(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "JDBC name pattern, e.g. '%calculate%'.", required = false) String namePattern,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listRoutines (schema={}, pattern={})", schema, namePattern);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            List<RoutineEntry> r = ctx.metadata().listRoutines(schema, namePattern);
            ToolLogger.completed(log, "listRoutines", start);
            return new ListRoutinesResult(r);
        } catch (SQLException e) {
            ToolLogger.failed(log, "listRoutines", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "Get the source code of a function / procedure / package (body, where applicable).",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String getRoutineDefinition(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String name,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: getRoutineDefinition (schema={}, name={})", schema, name);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String source = ctx.metadata().routineSource(schema, name);
            ToolLogger.completed(log, "getRoutineDefinition", start);
            if (source == null || source.isEmpty()) {
                throw errors.notFoundException("routine", name);
            }
            return source;
        } catch (SQLException e) {
            ToolLogger.failed(log, "getRoutineDefinition", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "List sequences in a schema (or in all schemas if schema is omitted).",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListSequencesResult listSequences(
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: listSequences (schema={})", schema);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            List<SequenceEntry> r = ctx.metadata().listSequences(schema);
            ToolLogger.completed(log, "listSequences", start);
            return new ListSequencesResult(r);
        } catch (SQLException e) {
            ToolLogger.failed(log, "listSequences", start, e.getMessage());
            throw errors.sqlException(e);
        }
    }

    @McpTool(
            description = "Search non-system tables, views, materialized views, routines, packages, sequences " +
            "and synonyms by case-insensitive name pattern; returns up to 200 matches.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SearchObjectsResult searchObjects(
            @McpToolParam(description = "Name pattern — plain substring (auto-wrapped in %..%) or explicit pattern with % / _") String namePattern,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: searchObjects (pattern={})", namePattern);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            List<SearchObjectEntry> r = ctx.metadata().searchObjects(namePattern);
            ToolLogger.completed(log, "searchObjects", start);
            return new SearchObjectsResult(r);
        } catch (SQLException e) {
            ToolLogger.failed(log, "searchObjects", start, e.getMessage());
            throw errors.sqlException(e);
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
