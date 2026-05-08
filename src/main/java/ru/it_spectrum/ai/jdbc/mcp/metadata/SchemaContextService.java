package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.it_spectrum.ai.jdbc.mcp.dialect.SqlDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;
import ru.it_spectrum.ai.jdbc.mcp.usage.UsageCatalogService;

import java.sql.SQLException;
import java.util.Map;

/**
 * Facade for high-level schema context operations exposed by MCP tools.
 */
@Service
public class SchemaContextService {

    private final SchemaOverviewService overview;
    private final SchemaTableContextService tableContext;
    private final SchemaJoinPathService joinPaths;
    private final SchemaLintService lint;
    private final SchemaBriefService brief;
    private final SchemaGraphService graph;
    private final SchemaQueryContextService queryContext;

    @Autowired
    public SchemaContextService(SchemaOverviewService overview,
                                SchemaTableContextService tableContext,
                                SchemaJoinPathService joinPaths,
                                SchemaLintService lint,
                                SchemaBriefService brief,
                                SchemaGraphService graph,
                                SchemaQueryContextService queryContext) {
        this.overview = overview;
        this.tableContext = tableContext;
        this.joinPaths = joinPaths;
        this.lint = lint;
        this.brief = brief;
        this.graph = graph;
        this.queryContext = queryContext;
    }

    public SchemaContextService(MetadataService metadata, StatsService stats,
                                SqlExecutor executor, SqlDialect dialect,
                                UsageCatalogService usageCatalog) {
        this(new SchemaOverviewService(metadata, stats, executor, dialect, usageCatalog),
                new SchemaTableContextService(metadata, stats, executor, dialect, usageCatalog),
                new SchemaJoinPathService(metadata, stats, executor, dialect, usageCatalog),
                new SchemaLintService(metadata, stats, executor, dialect, usageCatalog),
                new SchemaBriefService(metadata, stats, executor, dialect, usageCatalog),
                new SchemaGraphService(metadata, stats, executor, dialect, usageCatalog),
                new SchemaQueryContextService(metadata, stats, executor, dialect, usageCatalog));
    }

    public Map<String, Object> schemaOverview(String schema, String namePattern,
                                              Boolean includeViews, Boolean includeStats,
                                              Boolean includeObserved,
                                              Integer maxTables) throws SQLException {
        return overview.schemaOverview(schema, namePattern, includeViews, includeStats,
                includeObserved, maxTables);
    }

    public Map<String, Object> tableContext(String schema, String table, Integer depth,
                                            Boolean includeIncoming, Boolean includeStats,
                                            Boolean includeObserved)
            throws SQLException {
        return tableContext.tableContext(schema, table, depth, includeIncoming, includeStats,
                includeObserved);
    }

    public Map<String, Object> findJoinPaths(String fromSchema, String fromTable,
                                             String toSchema, String toTable,
                                             Integer maxDepth, Integer maxPaths,
                                             Integer scanLimit,
                                             Boolean includeObserved) throws SQLException {
        return joinPaths.findJoinPaths(fromSchema, fromTable, toSchema, toTable, maxDepth, maxPaths,
                scanLimit, includeObserved);
    }

    public Map<String, Object> schemaLint(String schema, String table, String checks,
                                          Integer maxTables, Integer maxFindings) throws SQLException {
        return lint.schemaLint(schema, table, checks, maxTables, maxFindings);
    }

    public String schemaBrief(String schema, String terms, Integer maxTables) throws SQLException {
        return brief.schemaBrief(schema, terms, maxTables);
    }

    public Map<String, Object> schemaGraph(String schema, Integer maxTables,
                                           String fromTable, String toTable,
                                           Integer maxDepth) throws SQLException {
        return graph.schemaGraph(schema, maxTables, fromTable, toTable, maxDepth);
    }

    public String schemaGraphDot(String schema, String tables) throws SQLException {
        return graph.schemaGraphDot(schema, tables);
    }

    public Map<String, Object> queryContext(String schema, String terms, String tables,
                                            Boolean includeSamples, Integer maxTables) throws SQLException {
        return queryContext.queryContext(schema, terms, tables, includeSamples, maxTables);
    }
}
