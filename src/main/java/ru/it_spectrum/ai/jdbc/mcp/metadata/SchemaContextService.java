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
                                              Boolean includeInferred, Boolean includeObserved,
                                              Integer maxTables) throws SQLException {
        return overview.schemaOverview(schema, namePattern, includeViews, includeStats, includeInferred,
                includeObserved, maxTables);
    }

    public Map<String, Object> tableContext(String schema, String table, Integer depth,
                                            Boolean includeIncoming, Boolean includeStats,
                                            Boolean includeInferred, Boolean includeObserved,
                                            Integer inferredScanLimit)
            throws SQLException {
        return tableContext.tableContext(schema, table, depth, includeIncoming, includeStats,
                includeInferred, includeObserved, inferredScanLimit);
    }

    public Map<String, Object> findJoinPaths(String fromSchema, String fromTable,
                                             String toSchema, String toTable,
                                             Integer maxDepth, Integer maxPaths,
                                             Integer scanLimit, Boolean includeInferred,
                                             Boolean includeObserved) throws SQLException {
        return joinPaths.findJoinPaths(fromSchema, fromTable, toSchema, toTable, maxDepth, maxPaths,
                scanLimit, includeInferred, includeObserved);
    }

    public Map<String, Object> schemaLint(String schema, String table, String checks,
                                          Integer maxTables, Integer maxFindings,
                                          Boolean includeInferred) throws SQLException {
        return lint.schemaLint(schema, table, checks, maxTables, maxFindings, includeInferred);
    }

    public String schemaBrief(String schema, String terms, Integer maxTables,
                              Boolean includeInferred) throws SQLException {
        return brief.schemaBrief(schema, terms, maxTables, includeInferred);
    }

    public Map<String, Object> schemaGraph(String schema, Integer maxTables,
                                           Boolean includeInferred,
                                           String fromTable, String toTable,
                                           Integer maxDepth) throws SQLException {
        return graph.schemaGraph(schema, maxTables, includeInferred, fromTable, toTable, maxDepth);
    }

    public String schemaGraphDot(String schema, String tables, Boolean includeInferred) throws SQLException {
        return graph.schemaGraphDot(schema, tables, includeInferred);
    }

    public Map<String, Object> queryContext(String schema, String terms, String tables,
                                            Boolean includeSamples, Integer maxTables,
                                            Boolean includeInferred) throws SQLException {
        return queryContext.queryContext(schema, terms, tables, includeSamples, maxTables, includeInferred);
    }
}
