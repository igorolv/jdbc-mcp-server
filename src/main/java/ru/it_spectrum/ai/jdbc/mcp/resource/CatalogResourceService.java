package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
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

/** Builds and serves catalog-qualified MCP resources over the existing metadata service. */
public final class CatalogResourceService {

    static final int RESOURCE_SCHEMA_VERSION = 1;
    static final String JSON_MIME_TYPE = "application/json";

    private final MetadataService metadata;
    private final StructureSnapshotStore snapshotStore;
    private final ObjectMapper mapper;
    private final String catalog;
    private final DatabaseKind databaseKind;
    private final CatalogResourceUris uris;

    public CatalogResourceService(MetadataService metadata, StructureSnapshotStore snapshotStore,
                                  ObjectMapper mapper, JdbcMcpProperties jdbcMcpProperties,
                                  DatabaseKind databaseKind) {
        this.metadata = metadata;
        this.snapshotStore = snapshotStore;
        this.mapper = mapper;
        this.catalog = jdbcMcpProperties.resolvedCatalogName();
        this.databaseKind = databaseKind;
        this.uris = new CatalogResourceUris(catalog);
    }

    public List<SyncResourceSpecification> resources() throws SQLException {
        McpSchema.Resource manifest = McpSchema.Resource.builder(uris.manifest(), "jdbc-catalog-manifest")
                .title("JDBC catalog " + catalog)
                .description("Snapshot identity, coverage, and resource templates for JDBC catalog '" + catalog + "'.")
                .mimeType(JSON_MIME_TYPE)
                .meta(declarationMeta())
                .build();
        List<SyncResourceSpecification> resources = new ArrayList<>();
        resources.add(new SyncResourceSpecification(manifest, this::readManifest));
        List<TableDescription> snapshotTables = snapshotStore.listSnapshotTableDescriptions();
        if (snapshotTables != null) {
            for (TableDescription table : snapshotTables) {
                if (table == null || table.schema() == null || table.schema().isBlank()
                        || table.name() == null || table.name().isBlank()) {
                    continue;
                }
                String qualifiedName = table.schema() + "." + table.name();
                McpSchema.Resource resource = McpSchema.Resource
                        .builder(uris.table(table.schema(), table.name()), qualifiedName)
                        .title(qualifiedName)
                        .description(tableDescription(table))
                        .mimeType(JSON_MIME_TYPE)
                        .meta(tableDeclarationMeta(table))
                        .build();
                resources.add(new SyncResourceSpecification(resource, this::readTable));
            }
        }
        return List.copyOf(resources);
    }

    public List<SyncResourceTemplateSpecification> resourceTemplates() {
        McpSchema.ResourceTemplate table = McpSchema.ResourceTemplate
                .builder(uris.tableTemplate(), "jdbc-table")
                .title("JDBC table description")
                .description("Columns, keys, indexes, constraints, relationships, and triggers for one table or view "
                        + "in JDBC catalog '" + catalog + "'.")
                .mimeType(JSON_MIME_TYPE)
                .meta(declarationMeta())
                .build();
        McpSchema.ResourceTemplate column = McpSchema.ResourceTemplate
                .builder(uris.columnTemplate(), "jdbc-column")
                .title("JDBC column context")
                .description("Definition and structural roles of one column in JDBC catalog '" + catalog + "'.")
                .mimeType(JSON_MIME_TYPE)
                .meta(declarationMeta())
                .build();
        return List.of(
                new SyncResourceTemplateSpecification(table, this::readTable),
                new SyncResourceTemplateSpecification(column, this::readColumn));
    }

    private ReadResourceResult readManifest(io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                            ReadResourceRequest request) {
        try {
            uris.requireManifest(request.uri());
            CatalogSnapshotInfo snapshot = snapshotStore.snapshotInfo();
            CatalogResourceManifest document = new CatalogResourceManifest(
                    RESOURCE_SCHEMA_VERSION,
                    catalog,
                    databaseKind.name(),
                    snapshot,
                    List.of(
                            new ResourceTemplateRef("jdbc-table", uris.tableTemplate(), JSON_MIME_TYPE),
                            new ResourceTemplateRef("jdbc-column", uris.columnTemplate(), JSON_MIME_TYPE)));
            return jsonResult(request.uri(), document, snapshot);
        } catch (IllegalArgumentException e) {
            throw invalidParams(e.getMessage());
        } catch (SQLException e) {
            throw internalError("Failed to read JDBC catalog manifest", e);
        }
    }

    private ReadResourceResult readTable(io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                         ReadResourceRequest request) {
        try {
            CatalogResourceUris.TableRef ref = uris.parseTable(request.uri());
            TableDescription table = metadata.describeTable(ref.schema(), ref.table());
            if (table == null) throw new IllegalArgumentException("Table not found: " + ref.schema() + "." + ref.table());
            return jsonResult(request.uri(),
                    new TableResourceDocument(RESOURCE_SCHEMA_VERSION, catalog, table),
                    snapshotStore.snapshotInfo());
        } catch (IllegalArgumentException e) {
            throw invalidParams(e.getMessage());
        } catch (SQLException e) {
            throw internalError("Failed to read JDBC table resource", e);
        }
    }

    private ReadResourceResult readColumn(io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                          ReadResourceRequest request) {
        try {
            CatalogResourceUris.ColumnRef ref = uris.parseColumn(request.uri());
            TableDescription table = metadata.describeTable(ref.schema(), ref.table());
            if (table == null) throw new IllegalArgumentException("Table not found: " + ref.schema() + "." + ref.table());
            Column column = findColumn(table, ref.column());
            if (column == null) {
                throw new IllegalArgumentException("Column not found: " + ref.schema() + "." + ref.table()
                        + "." + ref.column());
            }
            ColumnResourceDocument document = columnDocument(table, column);
            return jsonResult(request.uri(), document, snapshotStore.snapshotInfo());
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

    private Map<String, Object> tableDeclarationMeta(TableDescription table) {
        Map<String, Object> meta = new LinkedHashMap<>(declarationMeta());
        meta.put("schema", table.schema());
        meta.put("table", table.name());
        if (table.type() != null) meta.put("tableType", table.type());
        return Map.copyOf(meta);
    }

    private static String tableDescription(TableDescription table) {
        String type = table.type() == null || table.type().isBlank() ? "table-like object" : table.type();
        String base = type + " " + table.schema() + "." + table.name();
        List<String> details = new ArrayList<>();
        String remarks = singleLine(table.remarks());
        if (remarks != null) details.add(remarks);
        if (table.primaryKey() != null && table.primaryKey().columns() != null
                && !table.primaryKey().columns().isEmpty()) {
            details.add("PK: " + String.join(", ", table.primaryKey().columns()));
        }
        if (table.foreignKeys() != null && !table.foreignKeys().isEmpty()) {
            List<String> foreignKeys = new ArrayList<>();
            for (ForeignKey foreignKey : table.foreignKeys()) {
                String formatted = formatForeignKey(foreignKey);
                if (formatted != null) foreignKeys.add(formatted);
            }
            if (!foreignKeys.isEmpty()) details.add("FK: " + String.join(", ", foreignKeys));
        }
        return details.isEmpty() ? base : base + " — " + String.join("; ", details);
    }

    private static String formatForeignKey(ForeignKey foreignKey) {
        if (foreignKey == null || foreignKey.columns() == null || foreignKey.columns().isEmpty()
                || foreignKey.referencedTable() == null || foreignKey.referencedTable().isBlank()) {
            return null;
        }
        String target = foreignKey.referencedTable();
        if (foreignKey.referencedSchema() != null && !foreignKey.referencedSchema().isBlank()) {
            target = foreignKey.referencedSchema() + "." + target;
        }
        if (foreignKey.referencedColumns() != null && !foreignKey.referencedColumns().isEmpty()) {
            target += "(" + String.join(", ", foreignKey.referencedColumns()) + ")";
        }
        return String.join(", ", foreignKey.columns()) + " → " + target;
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().replaceAll("\\s+", " ");
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

    private static McpError internalError(String message, Exception cause) {
        return McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                .message(message)
                .data(cause.getMessage())
                .build();
    }
}
