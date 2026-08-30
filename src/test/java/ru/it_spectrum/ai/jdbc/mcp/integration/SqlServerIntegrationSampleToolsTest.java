package ru.it_spectrum.ai.jdbc.mcp.integration;

import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class SqlServerIntegrationSampleToolsTest extends AbstractSqlServerToolsIntegrationTest {

    @Test
    void samplesRowsWithTopLimit() {
        ObjectNode sample = object(sampleTools().sampleRows("dbo", "customers", 1, null));

        assertThat(field(sample, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(sample, "truncated").asBoolean()).isFalse();
        assertThat(field(row(sample, 0), "name").asText()).isIn("Alice", "Bob");
    }
}
