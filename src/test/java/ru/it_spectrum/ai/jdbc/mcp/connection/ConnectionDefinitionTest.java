package ru.it_spectrum.ai.jdbc.mcp.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionDefinitionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "ssj",
            "ssj-ws",
            "ssj_ws",
            "ssj.dev",
            "ssj@dev",
            "ssj-ek-export@tst",
            "nsi-ui@next",
            "a@b",
            "1@2",
    })
    void acceptsServiceAtStandNames(String name) {
        assertThat(ConnectionDefinition.requireValidName(name, "in a test")).isEqualTo(name);
    }

    @Test
    void acceptsTheLongestAllowedName() {
        String name = "s".repeat(60) + "@dev";

        assertThat(name).hasSize(ConnectionDefinition.MAX_NAME_LENGTH);
        assertThat(ConnectionDefinition.requireValidName(name, "in a test")).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "@dev",
            "ssj@",
            "@",
            "ssj@dev@tst",
            "ssj dev",
            "ssj/dev",
            "ssj\\dev",
            "ssj:dev",
            "ssj#dev",
            "",
            "   ",
            ".",
            "..",
    })
    void rejectsNamesThatWouldNotSurviveAPathOrAUri(String name) {
        assertThatThrownBy(() -> ConnectionDefinition.requireValidName(name, "in a test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid connection name");
    }

    @Test
    void rejectsNullAndOverlongNames() {
        assertThatThrownBy(() -> ConnectionDefinition.requireValidName(null, "in a test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConnectionDefinition.requireValidName(
                "s".repeat(ConnectionDefinition.MAX_NAME_LENGTH + 1), "in a test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64 characters");
    }

    @Test
    void namesTheOffendingValueAndTheRuleItBroke() {
        assertThatThrownBy(() -> ConnectionDefinition.requireValidName("ssj@", "in connections.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'ssj@'")
                .hasMessageContaining("in connections.json")
                .hasMessageContaining(ConnectionDefinition.NAME_PATTERN.pattern())
                .hasMessageContaining("directory names");
    }
}
