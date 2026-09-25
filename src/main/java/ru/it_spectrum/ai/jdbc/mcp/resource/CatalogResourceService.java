package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncCompletionSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.model.Opaque;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.CheckConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.IncomingForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.UniqueConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogResourceManifest;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogResourceManifest.ResourceTemplateRef;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogSnapshotInfo;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.ColumnResourceDocument;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.TableResourceDocument;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Builds and serves catalog-qualified MCP resources over the existing metadata service.
 *
 * <p>Each catalog publishes one concrete resource (its manifest) plus two URI templates, for a table
 * and for a column. Tables are deliberately <em>not</em> listed one by one: a schema with thousands
 * of tables would turn {@code resources/list} into a dump of the catalog, and the list would go stale
 * after every {@code rebuildCatalog}. Clients discover names through {@code completion/complete} on the
 * template arguments instead, answered from the local snapshot on every call.
 */
public final class CatalogResourceService {

    static final int RESOURCE_SCHEMA_VERSION = 1;
    static final String JSON_MIME_TYPE = "application/json";
    /** The MCP spec caps a completion response at 100 values. */
    static final int MAX_COMPLETION_VALUES = 100;

    private static final CatalogSnapshotInfo NO_SNAPSHOT = new CatalogSnapshotInfo(0, 0L, null, List.of());

    private final Supplier<MetadataService> metadata;
    private final Supplier<StructureSnapshotStore> snapshotStore;
    private final BooleanSupplier snapshotPresent;
    private final ObjectMapper mapper;
    private final String catalog;
    private final DatabaseKind databaseKind;
    private final CatalogResourceUris uris;

    /**
     * @param metadata        supplied lazily: the live-database fallback behind {@code describeTable}
     *                        must not be built — nor its pool — until a client actually reads a resource
     * @param snapshotStore   supplied lazily for the same reason
     * @param snapshotPresent whether the local catalog file exists; manifest reads and completions
     *                        consult the store only when it does, so they never create an empty catalog
     */
    public CatalogResourceService(Supplier<MetadataService> metadata, Supplier<StructureSnapshotStore> snapshotStore,
                                  BooleanSupplier snapshotPresent, ObjectMapper mapper,
                                  JdbcMcpProperties jdbcMcpProperties, DatabaseKind databaseKind) {
        this.metadata = metadata;
        this.snapshotStore = snapshotStore;
        this.snapshotPresent = snapshotPresent;
        this.mapper = mapper;
        this.catalog = jdbcMcpProperties.resolvedCatalogName();
        this.databaseKind = databaseKind;
        this.uris = new CatalogResourceUris(catalog);
    }

    public List<SyncResourceSpecification> resources() {
        McpSchema.Resource manifest = McpSchema.Resource.builder(uris.manifest(), manifestName())
                .title("JDBC catalog " + catalog)
                .description("Snapshot identity, coverage, and resource templates for JDBC catalog '" + catalog + "'.")
                .mimeType(JSON_MIME_TYPE)
                .meta(declarationMeta())
                .build();
        return List.of(new SyncResourceSpecification(manifest, this::readManifest));
    }

    public List<SyncResourceTemplateSpecification> resourceTemplates() {
        McpSchema.ResourceTemplate table = McpSchema.ResourceTemplate
                .builder(uris.tableTemplate(), tableTemplateName())
                .title("Table in JDBC catalog " + catalog)
                .description("Columns, keys, indexes, constraints, relationships, and triggers for one table or view "
                        + "in JDBC catalog '" + catalog + "'.")
                .mimeType(JSON_MIME_TYPE)
                .meta(declarationMeta())
                .build();
        McpSchema.ResourceTemplate column = McpSchema.ResourceTemplate
                .builder(uris.columnTemplate(), columnTemplateName())
                .title("Column in JDBC catalog " + catalog)
                .description("Definition and structural roles of one column in JDBC catalog '" + catalog + "'.")
                .mimeType(JSON_MIME_TYPE)
                .meta(declarationMeta())
                .build();
        return List.of(
                new SyncResourceTemplateSpecification(table, this::readTable),
                new SyncResourceTemplateSpecification(column, this::readColumn));
    }

    /** Argument completion for both templates: schema, table and column names from the local snapshot. */
    public List<SyncCompletionSpecification> completions() {
        return List.of(
                new SyncCompletionSpecification(new McpSchema.ResourceReference(uris.tableTemplate()), this::complete),
                new SyncCompletionSpecification(new McpSchema.ResourceReference(uris.columnTemplate()), this::complete));
    }

    private CompleteResult complete(McpSyncServerExchange exchange, CompleteRequest request) {
        if (request.argument() == null || request.argument().name() == null || !snapshotPresent.getAsBoolean()) {
            return completion(List.of());
        }
        String prefix = request.argument().value() == null ? "" : request.argument().value();
        Map<String, String> context = request.context() == null || request.context().arguments() == null
                ? Map.of() : request.context().arguments();
        String schema = context.get("schema");
        String table = context.get("table");
        int limit = MAX_COMPLETION_VALUES + 1;
        try {
            StructureSnapshotStore store = snapshotStore.get();
            List<String> values = switch (request.argument().name()) {
                case "schema" -> store.snapshotSchemaNames(prefix, limit);
                case "table" -> isBlank(schema) ? List.of() : store.snapshotTableNames(schema, prefix, limit);
                case "column" -> isBlank(schema) || isBlank(table)
                        ? List.of() : store.snapshotColumnNames(schema, table, prefix, limit);
                default -> List.of();
            };
            return completion(values);
        } catch (SQLException e) {
            throw internalError("Failed to complete JDBC resource argument", e);
        }
    }

    private static CompleteResult completion(List<String> values) {
        boolean hasMore = values.size() > MAX_COMPLETION_VALUES;
        List<String> page = hasMore ? values.subList(0, MAX_COMPLETION_VALUES) : values;
        return new CompleteResult(new CompleteResult.CompleteCompletion(List.copyOf(page), null, hasMore));
    }

    private String manifestName() {
        return catalog + "/manifest";
    }

    private String tableTemplateName() {
        return catalog + "/table";
    }

    private String columnTemplateName() {
        return catalog + "/column";
    }

    private ReadResourceResult readManifest(McpSyncServerExchange exchange, ReadResourceRequest request) {
        try {
            uris.requireManifest(request.uri());
            CatalogSnapshotInfo snapshot = snapshotInfo();
            CatalogResourceManifest document = new CatalogResourceManifest(
                    RESOURCE_SCHEMA_VERSION,
                    catalog,
                    databaseKind.name(),
                    snapshot,
                    List.of(
                            new ResourceTemplateRef(tableTemplateName(), uris.tableTemplate(), JSON_MIME_TYPE),
                            new ResourceTemplateRef(columnTemplateName(), uris.columnTemplate(), JSON_MIME_TYPE)));
            return jsonResult(request.uri(), document, snapshot);
        } catch (IllegalArgumentException e) {
            throw invalidParams(e.getMessage());
        } catch (SQLException e) {
            throw internalError("Failed to read JDBC catalog manifest", e);
        }
    }

    private ReadResourceResult readTable(McpSyncServerExchange exchange, ReadResourceRequest request) {
        try {
            CatalogResourceUris.TableRef ref = uris.parseTable(request.uri());
            TableDescription table = metadata.get().describeTable(ref.schema(), ref.table());
            if (isMissing(table)) {
                throw notFound(request.uri(), "Table not found: " + ref.schema() + "." + ref.table());
            }
            return jsonResult(request.uri(),
                    new TableResourceDocument(RESOURCE_SCHEMA_VERSION, catalog, table),
                    snapshotInfo());
        } catch (IllegalArgumentException e) {
            throw invalidParams(e.getMessage());
        } catch (SQLException e) {
            throw internalError("Failed to read JDBC table resource", e);
        }
    }

    private ReadResourceResult readColumn(McpSyncServerExchange exchange, ReadResourceRequest request) {
        try {
            CatalogResourceUris.ColumnRef ref = uris.parseColumn(request.uri());
            TableDescription table = metadata.get().describeTable(ref.schema(), ref.table());
            if (isMissing(table)) {
                throw notFound(request.uri(), "Table not found: " + ref.schema() + "." + ref.table());
            }
            Column column = findColumn(table, ref.column());
            if (column == null) {
                throw notFound(request.uri(), "Column not found: " + ref.schema() + "." + ref.table()
                        + "." + ref.column());
            }
            ColumnResourceDocument document = columnDocument(table, column);
            return jsonResult(request.uri(), document, snapshotInfo());
        } catch (IllegalArgumentException e) {
            throw invalidParams(e.getMessage());
        } catch (SQLException e) {
            throw internalError("Failed to read JDBC column resource", e);
        }
    }

    private ColumnResourceDocument columnDocument(TableDescription table, Column column) {
        String name = column.name();
        Integer primaryKeyPosition = null;
        if (table.primaryKey() != null && table.primaryKey().columns() != null) {
            int index = table.primaryKey().columns().indexOf(name);
            if (index >= 0) primaryKeyPosition = index + 1;
        }
        List<UniqueConstraint> unique = filterByColumn(table.uniqueConstraints(), name, UniqueConstraint::columns);
        List<Index> indexes = filterByColumn(table.indexes(), name, Index::columns);
        List<ForeignKey> outgoing = filterByColumn(table.foreignKeys(), name, ForeignKey::columns);
        List<CheckConstraint> checks = filterByColumn(table.checkConstraints(), name, CheckConstraint::columns);
        List<IncomingForeignKey> incoming = new ArrayList<>();
        if (table.referencedBy() != null) {
            for (Opaque<IncomingForeignKey> wrapped : table.referencedBy()) {
                if (wrapped == null) continue;
                IncomingForeignKey value = wrapped.unwrap();
                if (value != null && contains(value.toColumns(), name)) incoming.add(value);
            }
        }
        return new ColumnResourceDocument(
                RESOURCE_SCHEMA_VERSION, catalog, table.schema(), table.name(), column,
                primaryKeyPosition, unique, indexes, outgoing, incoming, checks);
    }

    private static Column findColumn(TableDescription table, String requested) {
        if (table.columns() == null) return null;
        for (Column column : table.columns()) {
            if (column != null && requested.equals(column.name())) return column;
        }
        Column match = null;
        for (Column column : table.columns()) {
            if (column != null && column.name() != null && requested.equalsIgnoreCase(column.name())) {
                if (match != null) return null;
                match = column;
            }
        }
        return match;
    }

    private static <T> List<T> filterByColumn(List<T> values, String column,
                                               java.util.function.Function<T, List<String>> columns) {
        if (values == null || values.isEmpty()) return List.of();
        List<T> out = new ArrayList<>();
        for (T value : values) {
            if (value != null && contains(columns.apply(value), column)) out.add(value);
        }
        return List.copyOf(out);
    }

    private static boolean contains(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(expected::equals);
    }

    private ReadResourceResult jsonResult(String uri, Object document, CatalogSnapshotInfo snapshot) {
        Map<String, Object> meta = contentMeta(snapshot);
        try {
            TextResourceContents content = TextResourceContents.builder(uri, mapper.writeValueAsString(document))
                    .mimeType(JSON_MIME_TYPE)
                    .meta(meta)
                    .build();
            return ReadResourceResult.builder(List.of(content)).meta(meta).build();
        } catch (JacksonException e) {
            throw internalError("Failed to serialize JDBC resource", e);
        }
    }

    private Map<String, Object> declarationMeta() {
        return Map.of(
                "catalog", catalog,
                "resourceSchemaVersion", RESOURCE_SCHEMA_VERSION);
    }

    private CatalogSnapshotInfo snapshotInfo() throws SQLException {
        return snapshotPresent.getAsBoolean() ? snapshotStore.get().snapshotInfo() : NO_SNAPSHOT;
    }

    /**
     * {@code describeTable} answers a name the database does not know with {@code null}. The untyped,
     * column-less placeholder older versions returned (and may have left in a shared catalog) is
     * treated the same way, so a resource never presents it as a real, empty table.
     */
    private static boolean isMissing(TableDescription table) {
        return table == null
                || (table.type() == null && (table.columns() == null || table.columns().isEmpty()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> contentMeta(CatalogSnapshotInfo snapshot) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("catalog", catalog);
        meta.put("resourceSchemaVersion", RESOURCE_SCHEMA_VERSION);
        meta.put("snapshotVersion", snapshot.snapshotVersion());
        if (snapshot.builtAt() != null) meta.put("snapshotBuiltAt", snapshot.builtAt());
        return Map.copyOf(meta);
    }

    private static McpError invalidParams(String message) {
        String safeMessage = message == null ? "Invalid JDBC resource URI" : message;
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                .message(safeMessage)
                .data(safeMessage)
                .build();
    }

    /** {@code -32002}, the code the MCP spec assigns to a resource that does not exist. */
    private static McpError notFound(String uri, String message) {
        return McpError.builder(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND)
                .message(message)
                .data(Map.of("uri", uri))
                .build();
    }

    private static McpError internalError(String message, Exception cause) {
        return McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                .message(message)
                .data(cause.getMessage())
                .build();
    }
}
