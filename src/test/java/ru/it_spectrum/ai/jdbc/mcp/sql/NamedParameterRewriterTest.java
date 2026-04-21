package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamedParameterRewriterTest {

    @Test
    void rewritesRepeatedNamedParametersInOrder() {
        NamedParameterRewriter.PreparedSql prepared = NamedParameterRewriter.rewrite(
                "SELECT * FROM t WHERE a = :id OR b = :id OR c = :name",
                Map.of("id", 42, "name", "alice"));

        assertThat(prepared.sql()).isEqualTo("SELECT * FROM t WHERE a = ? OR b = ? OR c = ?");
        assertThat(prepared.params()).containsExactly(42, 42, "alice");
    }

    @Test
    void leavesPositionalSqlUntouchedWhenNoNamedParametersPresent() {
        NamedParameterRewriter.PreparedSql prepared = NamedParameterRewriter.rewrite(
                "SELECT * FROM t WHERE a = ?",
                Map.of());

        assertThat(prepared.sql()).isEqualTo("SELECT * FROM t WHERE a = ?");
        assertThat(prepared.params()).hasSize(1);
    }

    @Test
    void throwsWhenNamedParameterIsMissing() {
        assertThatThrownBy(() -> NamedParameterRewriter.rewrite(
                "SELECT * FROM t WHERE a = :missing",
                Map.of("other", 1)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("missing");
    }
}
