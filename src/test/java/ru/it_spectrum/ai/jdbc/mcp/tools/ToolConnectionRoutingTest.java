package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.connection.ConnectionRegistry;
import ru.it_spectrum.ai.jdbc.mcp.connection.TestConnections;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.metadata.MetadataService;
import ru.it_spectrum.ai.jdbc.mcp.model.connection.ListConnectionsResult;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ListSchemasResult;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The routing contract the tool classes share: the {@code connection} argument picks the database,
 * omitting it lands on the default, and an unknown name is an argument error that says what exists.
 */
class ToolConnectionRoutingTest {

    private static final JdbcProperties PROPERTIES = new JdbcProperties(
            "jdbc:postgresql://localhost:5432/db", "u", "p", "public", 30, 1000, 500, "strict",
            40, 0, 10_000, 5_000, 60_000);

    private final MetadataService ordersMetadata = mock(MetadataService.class);
    private final MetadataService billingMetadata = mock(MetadataService.class);
    private final ConnectionRegistry connections = TestConnections.registry(
            TestConnections.context("orders", PROPERTIES, ordersMetadata),
            TestConnections.context("billing", PROPERTIES, billingMetadata));
    private final JsonResponses json = new JsonResponses(new JsonConfig().jdbcMcpObjectMapper());
    private final ToolErrors errors = new ToolErrors(json);
    private final MetadataTools metadataTools = new MetadataTools(connections, json, errors);

    @Test
    void anExplicitConnectionSelectsThatDatabaseOnly() throws SQLException {
        when(billingMetadata.listSchemas(false)).thenReturn(List.of("billing_schema"));

        ListSchemasResult result = metadataTools.listSchemas(false, "billing");

        assertThat(result.schemas()).containsExactly("billing_schema");
        verifyNoInteractions(ordersMetadata);
    }

    @Test
    void omittingTheConnectionUsesTheDefault() throws SQLException {
        when(ordersMetadata.listSchemas(false)).thenReturn(List.of("orders_schema"));

        ListSchemasResult result = metadataTools.listSchemas(false, null);

        assertThat(result.schemas()).containsExactly("orders_schema");
        verifyNoInteractions(billingMetadata);
    }

    @Test
    void anUnknownConnectionIsAnArgumentErrorListingTheAvailableOnes() {
        assertThatThrownBy(() -> metadataTools.listSchemas(false, "orderz"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"kind\":\"argument\"")
                .hasMessageContaining("Unknown connection 'orderz'")
                .hasMessageContaining("orders, billing");
        verifyNoInteractions(ordersMetadata, billingMetadata);
    }

    @Test
    void listConnectionsDescribesTheRoutingWithoutTouchingAnyDatabase() {
        ListConnectionsResult result = new ConnectionTools(connections).listConnections();

        assertThat(result.defaultConnection()).isEqualTo("orders");
        assertThat(result.connections()).extracting("name").containsExactly("orders", "billing");
        assertThat(result.connections().getFirst().isDefault()).isTrue();
        assertThat(result.connections().getFirst().kind()).isEqualTo("PostgreSQL");
        assertThat(result.connections().get(1).isDefault()).isFalse();
        verifyNoInteractions(ordersMetadata, billingMetadata);
    }
}
