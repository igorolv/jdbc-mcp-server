package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class PostgresIntegrationSampleToolsTest extends AbstractPostgresToolsIntegrationTest {

    @Test
    void sampleRowsReturnsLimitedJsonResult() {
        ObjectNode result = object(sampleTools().sampleRows(connection(), "public", "customers", 1));
        assertThat(field(result, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(result, "truncated").asBoolean()).isFalse();
        assertThat(field(row(result, 0), "name").asText()).isEqualTo("Alice");
    }

}
