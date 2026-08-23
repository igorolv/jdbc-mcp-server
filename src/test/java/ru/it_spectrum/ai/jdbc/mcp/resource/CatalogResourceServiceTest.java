package ru.it_spectrum.ai.jdbc.mcp.resource;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
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
import ru.it_spectrum.ai.jdbc.mcp.model.resource.CatalogSnapshotInfo;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.ColumnResourceDocument;
import ru.it_spectrum.ai.jdbc.mcp.model.resource.TableResourceDocument;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
        when(snapshotStore.listSnapshotTableDescriptions()).thenReturn(List.of(customer()));
        service = new CatalogResourceService(
                metadata, snapshotStore, MAPPER,
                new JdbcMcpProperties("build/test-resource-data", "orders/eu"),
                DatabaseKind.POSTGRESQL);
    }

    @Test
    void manifestAndTemplatesAreQualifiedByTheConfiguredCatalog() throws Exception {
        List<SyncResourceSpecification> resources = service.resources();
        var resource = resources.getFirst().resource();
        assertThat(resource.uri()).isEqualTo("jdbc-mcp://catalog/orders%2Feu/manifest");
        assertThat(resource.meta()).containsEntry("catalog", "orders/eu");
        assertThat(resources).hasSize(2);
        assertThat(resources.get(1).resource().name()).isEqualTo("public.customer");
        assertThat(resources.get(1).resource().uri())
                .isEqualTo("jdbc-mcp://catalog/orders%2Feu/schemas/public/tables/customer");
        assertThat(resources.get(1).resource().description())
                .isEqualTo("TABLE public.customer — People we bill; PK: id; "
                        + "FK: org_id → public.org(id)");
        assertThat(resources.get(1).resource().meta())
                .containsEntry("schema", "public")
                .containsEntry("table", "customer")
                .containsEntry("tableType", "TABLE");

        List<SyncResourceTemplateSpecification> templates = service.resourceTemplates();
        assertThat(templates).extracting(t -> t.resourceTemplate().uriTemplate())
                .containsExactly(
                        "jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}",
                        "jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}/columns/{column}");

        ReadResourceResult result = service.resources().getFirst().readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(resource.uri()).build());
        CatalogResourceManifest manifest = MAPPER.readValue(text(result), CatalogResourceManifest.class);
        assertThat(manifest.catalog()).isEqualTo("orders/eu");
        assertThat(manifest.databaseKind()).isEqualTo("POSTGRESQL");
        assertThat(manifest.snapshot()).isEqualTo(SNAPSHOT);
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
    void concreteTableResourceUsesTheSameReadHandlerAsTheTemplate() throws Exception {
        TableDescription table = customer();
        when(metadata.describeTable("public", "customer")).thenReturn(table);
        var spec = service.resources().get(1);

        ReadResourceResult result = spec.readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(spec.resource().uri()).build());
        TableResourceDocument document = MAPPER.readValue(text(result), TableResourceDocument.class);

        assertThat(document.table()).isEqualTo(table);
        assertThat(document.catalog()).isEqualTo("orders/eu");
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
    void aTemplateCannotReadAnotherCatalog() {
        SyncResourceTemplateSpecification spec = service.resourceTemplates().getFirst();

        assertThatThrownBy(() -> spec.readHandler().apply(null,
                McpSchema.ReadResourceRequest.builder(
                        "jdbc-mcp://catalog/billing/schemas/public/tables/customer").build()))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("another JDBC catalog");
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
