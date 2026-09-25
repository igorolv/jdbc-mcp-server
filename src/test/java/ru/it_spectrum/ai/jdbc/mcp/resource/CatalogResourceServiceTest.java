package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.DatabaseKind;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcMcpProperties;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore;
import ru.it_spectrum.ai.jdbc.mcp.model.Opaque;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.CheckConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.IncomingForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.UniqueConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogResourceManifest;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogResourceManifest.ResourceTemplateRef;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogSnapshotInfo;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.ColumnResourceDocument;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.TableResourceDocument;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogResourceServiceTest {

    private static final ObjectMapper MAPPER = new JsonConfig().jdbcMcpObjectMapper();
    private static final CatalogSnapshotInfo SNAPSHOT =
            new CatalogSnapshotInfo(1, 7, "2026-08-23T12:00:00Z", List.of("public"));

    private MetadataService metadata;
    private StructureSnapshotStore snapshotStore;
    private CatalogResourceService service;

    @BeforeEach
    void setUp() throws Exception {
        metadata = mock(MetadataService.class);
        snapshotStore = mock(StructureSnapshotStore.class);
        when(snapshotStore.snapshotInfo()).thenReturn(SNAPSHOT);
        service = new CatalogResourceService(
                () -> metadata, () -> snapshotStore, () -> true, MAPPER,
                new JdbcMcpProperties("build/test-resource-data", "orders/eu"),
                DatabaseKind.POSTGRESQL);
    }

    @Test
    void manifestAndTemplatesAreQualifiedByTheConfiguredCatalog() throws Exception {
        List<SyncResourceSpecification> resources = service.resources();
        var resource = resources.getFirst().resource();
        assertThat(resource.uri()).isEqualTo("jdbc-mcp://catalog/orders%2Feu/manifest");
        assertThat(resource.meta()).containsEntry("catalog", "orders/eu");
        assertThat(resource.name()).isEqualTo("orders/eu/manifest");
        // Tables are reached through the template, never listed one by one.
        assertThat(resources).hasSize(1);

        List<SyncResourceTemplateSpecification> templates = service.resourceTemplates();
        assertThat(templates).extracting(t -> t.resourceTemplate().uriTemplate())
                .containsExactly(
                        "jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}",
                        "jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}/columns/{column}");
        assertThat(templates).extracting(t -> t.resourceTemplate().name())
                .containsExactly("orders/eu/table", "orders/eu/column");

        ReadResourceResult result = service.resources().getFirst().readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(resource.uri()).build());
        CatalogResourceManifest manifest = MAPPER.readValue(text(result), CatalogResourceManifest.class);
        assertThat(manifest.catalog()).isEqualTo("orders/eu");
        assertThat(manifest.databaseKind()).isEqualTo("POSTGRESQL");
        assertThat(manifest.snapshot()).isEqualTo(SNAPSHOT);
        assertThat(manifest.resourceTemplates()).extracting(ResourceTemplateRef::name)
                .containsExactly("orders/eu/table", "orders/eu/column");
    }

    @Test
    void aConnectionWithoutACatalogNeverTouchesTheStore() throws Exception {
        StructureSnapshotStore untouchable = mock(StructureSnapshotStore.class);
        CatalogResourceService noCatalog = new CatalogResourceService(
                () -> metadata, () -> untouchable, () -> false, MAPPER,
                new JdbcMcpProperties("build/test-resource-data", "orders"), DatabaseKind.POSTGRESQL);
        var spec = noCatalog.resources().getFirst();

        ReadResourceResult result = spec.readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(spec.resource().uri()).build());

        assertThat(MAPPER.readValue(text(result), CatalogResourceManifest.class).snapshot().snapshotVersion())
                .isZero();
        assertThat(complete(noCatalog, 0, "schema", "", Map.of()).completion().values()).isEmpty();
        verifyNoInteractions(untouchable);
    }

    @Test
    void completionsCoverBothTemplatesAndUseEarlierArgumentsAsContext() throws Exception {
        assertThat(service.completions())
                .extracting(c -> ((McpSchema.ResourceReference) c.referenceKey()).uri())
                .containsExactly(
                        "jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}",
                        "jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}/columns/{column}");
        when(snapshotStore.snapshotSchemaNames("pu", 101)).thenReturn(List.of("public"));
        when(snapshotStore.snapshotTableNames("public", "cu", 101)).thenReturn(List.of("customer"));
        when(snapshotStore.snapshotColumnNames("public", "customer", "", 101))
                .thenReturn(List.of("id", "org_id", "status"));

        assertThat(complete(service, 0, "schema", "pu", Map.of()).completion().values())
                .containsExactly("public");
        assertThat(complete(service, 0, "table", "cu", Map.of("schema", "public")).completion().values())
                .containsExactly("customer");
        CompleteResult columns = complete(service, 1, "column", "",
                Map.of("schema", "public", "table", "customer"));
        assertThat(columns.completion().values()).containsExactly("id", "org_id", "status");
        assertThat(columns.completion().hasMore()).isFalse();
        // Without the schema there is nothing to complete a table against.
        assertThat(complete(service, 0, "table", "cu", Map.of()).completion().values()).isEmpty();
    }

    @Test
    void completionIsCappedAtTheSpecLimitAndFlagsMore() throws Exception {
        List<String> many = IntStream.rangeClosed(1, 101).mapToObj(i -> "t" + i).toList();
        when(snapshotStore.snapshotTableNames("public", "", 101)).thenReturn(many);

        CompleteResult result = complete(service, 0, "table", "", Map.of("schema", "public"));

        assertThat(result.completion().values()).hasSize(100);
        assertThat(result.completion().hasMore()).isTrue();
    }

    @Test
    void aMissingTableOrColumnIsResourceNotFound() throws Exception {
        when(metadata.describeTable("public", "customer")).thenReturn(customer());
        String missingTable = "jdbc-mcp://catalog/orders%2Feu/schemas/public/tables/nope";
        String missingColumn = "jdbc-mcp://catalog/orders%2Feu/schemas/public/tables/customer/columns/nope";

        assertThatThrownBy(() -> service.resourceTemplates().getFirst().readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(missingTable).build()))
                .isInstanceOfSatisfying(McpError.class, e -> {
                    assertThat(e.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND);
                    assertThat(e.getJsonRpcError().data()).isEqualTo(Map.of("uri", missingTable));
                });
        assertThatThrownBy(() -> service.resourceTemplates().get(1).readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(missingColumn).build()))
                .isInstanceOfSatisfying(McpError.class, e -> assertThat(e.getJsonRpcError().code())
                        .isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND));
    }

    @Test
    void theUntypedPlaceholderDescribeTableReturnsForAnUnknownNameIsNotFound() throws Exception {
        when(metadata.describeTable("public", "ghost")).thenReturn(new TableDescription(
                "public", "ghost", null, null, List.of(), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        String uri = "jdbc-mcp://catalog/orders%2Feu/schemas/public/tables/ghost";

        assertThatThrownBy(() -> service.resourceTemplates().getFirst().readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(uri).build()))
                .isInstanceOfSatisfying(McpError.class, e -> assertThat(e.getJsonRpcError().code())
                        .isEqualTo(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND));
    }

    @Test
    void tableResourceUsesTheMetadataFacadeAndReturnsSnapshotProvenance() throws Exception {
        TableDescription table = customer();
        when(metadata.describeTable("public", "customer")).thenReturn(table);
        SyncResourceTemplateSpecification spec = service.resourceTemplates().getFirst();
        String uri = "jdbc-mcp://catalog/orders%2Feu/schemas/public/tables/customer";

        ReadResourceResult result = spec.readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(uri).build());
        TableResourceDocument document = MAPPER.readValue(text(result), TableResourceDocument.class);

        assertThat(document.catalog()).isEqualTo("orders/eu");
        assertThat(document.table()).isEqualTo(table);
        assertThat(result.meta()).containsEntry("snapshotVersion", 7L)
                .containsEntry("catalog", "orders/eu");
    }

    @Test
    void columnResourceReturnsOnlyStructuralObjectsThatUseTheColumn() throws Exception {
        when(metadata.describeTable("public", "customer")).thenReturn(customer());
        SyncResourceTemplateSpecification spec = service.resourceTemplates().get(1);
        String uri = "jdbc-mcp://catalog/orders%2Feu/schemas/public/tables/customer/columns/org_id";

        ReadResourceResult result = spec.readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(uri).build());
        ColumnResourceDocument document = MAPPER.readValue(text(result), ColumnResourceDocument.class);

        assertThat(document.column().name()).isEqualTo("org_id");
        assertThat(document.primaryKeyPosition()).isNull();
        assertThat(document.uniqueConstraints()).extracting(UniqueConstraint::name).containsExactly("customer_uq");
        assertThat(document.indexes()).extracting(Index::name).containsExactly("customer_org_ix");
        assertThat(document.outgoingForeignKeys()).extracting(ForeignKey::name)
                .containsExactly("customer_org_fk");
        assertThat(document.incomingForeignKeys()).isEmpty();
        assertThat(document.checkConstraints()).isEmpty();
    }

    @Test
    void aServiceAtStandCatalogPublishesAndReadsItsOwnResources() throws Exception {
        when(metadata.describeTable("public", "customer")).thenReturn(customer());
        CatalogResourceService atName = new CatalogResourceService(
                () -> metadata, () -> snapshotStore, () -> true, MAPPER,
                new JdbcMcpProperties("build/test-resource-data", "ssj@dev"),
                DatabaseKind.POSTGRESQL);

        String tableUri = "jdbc-mcp://catalog/ssj%40dev/schemas/public/tables/customer";
        List<SyncResourceSpecification> resources = atName.resources();
        assertThat(resources.getFirst().resource().uri())
                .isEqualTo("jdbc-mcp://catalog/ssj%40dev/manifest");
        assertThat(atName.resourceTemplates())
                .extracting(t -> t.resourceTemplate().uriTemplate())
                .containsExactly(
                        "jdbc-mcp://catalog/ssj%40dev/schemas/{schema}/tables/{table}",
                        "jdbc-mcp://catalog/ssj%40dev/schemas/{schema}/tables/{table}/columns/{column}");

        ReadResourceResult manifestResult = resources.getFirst().readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(resources.getFirst().resource().uri()).build());
        assertThat(MAPPER.readValue(text(manifestResult), CatalogResourceManifest.class).catalog())
                .isEqualTo("ssj@dev");

        ReadResourceResult tableResult = atName.resourceTemplates().getFirst().readHandler()
                .apply(null, McpSchema.ReadResourceRequest.builder(tableUri).build());
        assertThat(MAPPER.readValue(text(tableResult), TableResourceDocument.class).catalog())
                .isEqualTo("ssj@dev");
        assertThat(tableResult.meta()).containsEntry("catalog", "ssj@dev");
    }

    @Test
    void aTemplateCannotReadAnotherCatalog() {
        SyncResourceTemplateSpecification spec = service.resourceTemplates().getFirst();

        assertThatThrownBy(() -> spec.readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(
                        "jdbc-mcp://catalog/billing/schemas/public/tables/customer").build()))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("another JDBC catalog");
    }

    private static CompleteResult complete(CatalogResourceService target, int template, String argument,
                                           String value, Map<String, String> context) {
        var spec = target.completions().get(template);
        CompleteRequest request = new CompleteRequest(spec.referenceKey(),
                new CompleteRequest.CompleteArgument(argument, value),
                new CompleteRequest.CompleteContext(context));
        return spec.completionHandler().apply(null, request);
    }

    private static String text(ReadResourceResult result) {
        return ((TextResourceContents) result.contents().getFirst()).text();
    }

    private static TableDescription customer() {
        return new TableDescription(
                "public", "customer", "TABLE", "People we bill",
                List.of(
                        new Column("id", 1, "bigint", 19, null, false, null, "pk", Boolean.TRUE),
                        new Column("org_id", 2, "bigint", 19, null, true, null, null, null),
                        new Column("status", 3, "varchar", 16, null, true, "'NEW'", null, null)),
                new PrimaryKey("customer_pk", List.of("id")),
                List.of(new UniqueConstraint("customer_uq", List.of("org_id"))),
                List.of(
                        new Index("customer_org_ix", false, List.of("org_id")),
                        new Index("customer_status_ix", false, List.of("status"))),
                List.of(new ForeignKey("customer_org_fk", List.of("org_id"),
                        "public", "org", List.of("id"))),
                List.of(Opaque.of(new IncomingForeignKey("order_customer_fk", "public", "orders",
                        List.of("customer_id"), List.of("id")))),
                List.of(new CheckConstraint("customer_status_chk", List.of("status"),
                        "status IN ('NEW','ACTIVE')", List.of("NEW", "ACTIVE"))),
                List.of());
    }
}
