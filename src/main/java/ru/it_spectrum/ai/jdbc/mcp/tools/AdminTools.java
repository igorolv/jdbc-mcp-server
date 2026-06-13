package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.admin.RebuildCatalogResult;
import ru.it_spectrum.ai.jdbc.mcp.model.usage.UsageCatalogStatus;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.List;

/**
 * Administrative MCP tools that operate on the local catalog file rather than serving metadata
 * lookups. The single entry point builds a complete, distributable {@code <catalog>.db}.
 */
@Service
public class AdminTools {

    private static final Logger log = LoggerFactory.getLogger(AdminTools.class);

    private final MetadataService metadata;
    private final UsageCatalogService usageCatalog;
    private final JdbcMcpProperties jdbcMcpProperties;
    private final ToolErrors errors;

    public AdminTools(MetadataService metadata, UsageCatalogService usageCatalog,
                      JdbcMcpProperties jdbcMcpProperties, ToolErrors errors) {
        this.metadata = metadata;
        this.usageCatalog = usageCatalog;
        this.jdbcMcpProperties = jdbcMcpProperties;
        this.errors = errors;
    }

    @McpTool(
            description = "Build the local catalog file: capture structural metadata and rebuild the "
                    + "usage index for the given schemas, so later metadata and usage lookups are served "
                    + "from it and the file can be shipped or reused as a prebuilt catalog. 'schemas': "
                    + "comma-separated; omit for the configured scope or default schema. Returns the "
                    + "catalog file path, the captured schemas and the usage-index status.",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )
    public RebuildCatalogResult rebuildCatalog(
            @McpToolParam(description = "Schemas to capture into the structure snapshot (comma-separated). Omit to use the configured scope / default schema.", required = false) String schemas
    ) {
        log.info("Tool call: rebuildCatalog (schemas={})", schemas);
        long start = System.nanoTime();
        try {
            String[] parsed = parseSchemas(schemas);
            List<String> covered = parsed == null
                    ? metadata.rebuildStructureSnapshot()
                    : metadata.rebuildStructureSnapshot(List.of(parsed));
            UsageCatalogStatus usage = usageCatalog.rebuildFromSources();
            String catalogFile = jdbcMcpProperties.catalogDbFile().toAbsolutePath().toString();
            ToolLogger.completed(log, "rebuildCatalog", start);
            return new RebuildCatalogResult(catalogFile, covered, usage);
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
