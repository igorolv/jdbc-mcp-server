package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionContext;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.model.admin.RebuildCatalogResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;

import java.sql.SQLException;
import java.util.List;

/**
 * Administrative MCP tools that operate on the local catalog file rather than serving metadata
 * lookups. The single entry point builds a complete, distributable {@code <catalog>.db}.
 */
@Service
@ConditionalOnProperty(prefix = "jdbc-mcp.tools", name = "admin", havingValue = "true", matchIfMissing = true)
public class AdminTools {

    private static final Logger log = LoggerFactory.getLogger(AdminTools.class);

    private final ConnectionRegistry connections;
    private final ToolErrors errors;

    public AdminTools(ConnectionRegistry connections, ToolErrors errors) {
        this.connections = connections;
        this.errors = errors;
    }

    @McpTool(
            description = "Build a distributable local catalog by capturing schema metadata and rebuilding the "
                    + "usage index; return file path, captured schemas and usage status.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public RebuildCatalogResult rebuildCatalog(
            @McpToolParam(description = "Schemas to capture (CSV); omit for configured/default scope.", required = false) String schemas,
            @McpToolParam(description = ToolConnections.CONNECTION_PARAM, required = false) String connection
    ) {
        log.info("Tool call: rebuildCatalog (schemas={})", schemas);
        ConnectionContext ctx = ToolConnections.resolve(connections, errors, connection);
        long start = System.nanoTime();
        try {
            String[] parsed = parseSchemas(schemas);
            List<String> covered = parsed == null
                    ? ctx.metadata().rebuildStructureSnapshot()
                    : ctx.metadata().rebuildStructureSnapshot(List.of(parsed));
            UsageCatalogStatus usage = ctx.usageCatalog().rebuildFromSources();
            ctx.catalogStorage().checkpointForDistribution();
            String catalogFile = ctx.catalogProperties().catalogDbFile().toAbsolutePath().toString();
            ToolLogger.completed(log, "rebuildCatalog", start);
            return new RebuildCatalogResult(ctx.name(), catalogFile, covered, usage);
        } catch (SQLException e) {
            ToolLogger.failed(log, "rebuildCatalog", start, e.getMessage());
            throw errors.sqlException(e);
        } catch (RuntimeException e) {
            ToolLogger.failed(log, "rebuildCatalog", start, e.getMessage());
            throw errors.unexpectedException(e);
        }
    }

    private static String[] parseSchemas(String schemas) {
        if (schemas == null || schemas.isBlank()) return null;
        List<String> cleaned = new java.util.ArrayList<>();
        for (String part : schemas.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) cleaned.add(s);
        }
        return cleaned.isEmpty() ? null : cleaned.toArray(new String[0]);
    }
}
