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
            description = "Discover schema names visible to the current user when the target schema is unknown. " +
            "Use listTables next to enumerate objects in one schema.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListSchemasResult listSchemas(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "Include system schemas. Default false.", required = false) Boolean includeSystem
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
            description = "Enumerate table and view names in a known schema, optionally filtered by name or type. " +
            "Does not return fields/columns; use describeTable for one object's structure.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListTablesResult listTables(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "Omit to use JDBC_DEFAULT_SCHEMA or the current schema.", required = false) String schema,
            @McpToolParam(description = "JDBC pattern ('%' any, '_' one character).", required = false) String namePattern,
            @McpToolParam(description = "JDBC table types CSV (default TABLE,VIEW,MATERIALIZED VIEW): TABLE,VIEW,MATERIALIZED VIEW,SYSTEM TABLE,GLOBAL TEMPORARY,LOCAL TEMPORARY,ALIAS,SYNONYM", required = false) String types
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
            description = "Inspect one known table or view. Use for its fields/columns, types, nullability, defaults, " +
            "comments, keys, indexes, constraints or triggers. Returns full metadata for that object only; use " +
            "tableContext when nearby relationships or joins are also needed.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public TableDescription describeTable(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table
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
            description = "Return the full definition/body of one known table trigger. For trigger names and compact " +
            "metadata, use describeTable first.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String getTriggerDefinition(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String table,
            @McpToolParam(description = "") String trigger
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
            description = "Return the SQL text that defines one known view or materialized view. For its exposed " +
            "fields, keys and other object metadata, use describeTable.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String getViewDefinition(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String name
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
            description = "Discover function, procedure and package names in a schema, optionally by name pattern. " +
            "Use getRoutineDefinition when the source of one known routine is needed.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListRoutinesResult listRoutines(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "JDBC name pattern, e.g. '%calculate%'.", required = false) String namePattern
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
            description = "Return the source code of one known function, procedure or package, including its body " +
            "where available. Use listRoutines to discover routine names.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public String getRoutineDefinition(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema,
            @McpToolParam(description = "") String name
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
            description = "Discover sequence names and metadata in one schema, or across all schemas when schema is " +
            "omitted.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public ListSequencesResult listSequences(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "", required = false) String schema
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
            description = "Find database objects when only a full or partial name is known. Searches non-system " +
            "tables, views, routines, packages, sequences and synonyms case-insensitively; use describeTable after " +
            "finding a table or view whose structure is needed.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public SearchObjectsResult searchObjects(
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM) String connection,
            @McpToolParam(description = "Name pattern — plain substring (auto-wrapped in %..%) or explicit pattern with % / _") String namePattern
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
