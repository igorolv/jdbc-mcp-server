package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlParameterBindingResolverTest {

    @Test
    void detectModeIgnoresQuotedTextAndPgCastSyntax() {
        String sql = "SELECT '?', ':userId', now()::timestamp";

        assertThat(SqlParameterBindingResolver.detectMode(sql))
                .isEqualTo(SqlParameterBindingResolver.ParameterMode.NONE);
    }

    @Test
    void resolveAllowsNoPlaceholdersWithoutBindingContainers() {
        SqlParameterBindingResolver.Binding binding =
                SqlParameterBindingResolver.resolve("SELECT 1", null, null);

        assertThat(binding.mode()).isEqualTo(SqlParameterBindingResolver.ParameterMode.NONE);
        assertThat(binding.params()).isNull();
        assertThat(binding.namedParams()).isNull();
    }

    @Test
    void resolveRequiresParamsForPositionalPlaceholders() {
        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM orders WHERE id = ?",
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains '?' placeholders")
                .hasMessageContaining("'params'");
    }

    @Test
    void resolveRequiresNamedParamsForNamedPlaceholders() {
        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM orders WHERE id = :orderId",
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("named placeholders")
                .hasMessageContaining("'namedParams'");
    }

    @Test
    void resolveRejectsMixedPlaceholderStyles() {
        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM orders WHERE id = ? AND status = :status",
                List.of(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mixes '?' and ':name'");
    }

    @Test
    void resolveRejectsWrongBindingContainer() {
        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM orders WHERE id = :orderId",
                List.of(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'namedParams'")
                .hasMessageContaining("not 'params'");
    }

    @Test
    void resolveReturnsNamedBindingForNamedPlaceholders() {
        SqlParameterBindingResolver.Binding binding =
                SqlParameterBindingResolver.resolve(
                        "SELECT * FROM orders WHERE id = :orderId",
                        null, Map.of("orderId", 7));

        assertThat(binding.mode()).isEqualTo(SqlParameterBindingResolver.ParameterMode.NAMED);
        assertThat(binding.namedParams()).containsEntry("orderId", 7);
        assertThat(binding.params()).isNull();
    }
}
