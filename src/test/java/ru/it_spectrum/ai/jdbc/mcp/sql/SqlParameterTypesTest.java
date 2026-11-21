package ru.it_spectrum.ai.jdbc.mcp.sql;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParameterTypes.TypedNull;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SqlParameterTypesTest {

    @Test
    void convertsAllSixStrictTypesCaseInsensitively() {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        assertThat(SqlParameterTypes.convertValue(SqlParamType.parse("DATE"), "2026-11-20", "d"))
                .isEqualTo(Date.valueOf(LocalDate.of(2026, 11, 20)));
        assertThat(SqlParameterTypes.convertValue(SqlParamType.parse("Timestamp"), "2026-11-20T15:30:45", "ts"))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 15, 30, 45)));
        assertThat(SqlParameterTypes.convertValue(SqlParamType.parse("timestamptz"), "2026-11-20T15:30:45+03:00", "tz"))
                .isEqualTo(OffsetDateTime.of(2026, 11, 20, 15, 30, 45, 0, ZoneOffset.ofHours(3)));
        assertThat(SqlParameterTypes.convertValue(SqlParamType.parse("UUID"), uuid.toString(), "id"))
                .isEqualTo(uuid);
        assertThat(SqlParameterTypes.convertValue(SqlParamType.parse("INTEGER"), "9007199254740993", "n1"))
                .isEqualTo(9007199254740993L);
        assertThat(SqlParameterTypes.convertValue(SqlParamType.parse("integer"), 42, "n2"))
                .isEqualTo(42L);
        assertThat(SqlParameterTypes.convertValue(SqlParamType.parse("DECIMAL"), "123456789012345.67", "d1"))
                .isEqualTo(new BigDecimal("123456789012345.67"));
    }

    @Test
    void rejectsRemovedAliasesAndDdlTypeNames() {
        for (String unsupported : List.of("datetime", "offsetdatetime", "int", "bigint", "long",
                "numeric", "number", "boolean", "bool", "string", "varchar", "text",
                "VARCHAR2", "NUMBER(10)", "int4", "xml")) {
            assertThatThrownBy(() -> SqlParamType.parse(unsupported))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported paramType");
        }
    }

    @Test
    void timestampAcceptsSpaceSeparatorAndDateOnly() {
        assertThat(SqlParameterTypes.convertValue(SqlParamType.timestamp, "2026-11-20 15:30:45", "ts"))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 15, 30, 45)));
        assertThat(SqlParameterTypes.convertValue(SqlParamType.timestamp, "2026-11-20 15:30:45.5", "ts"))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 15, 30, 45, 500_000_000)));
        assertThat(SqlParameterTypes.convertValue(SqlParamType.timestamp, "2026-11-20", "ts"))
                .isEqualTo(Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 0, 0, 0)));
    }

    @Test
    void timestampRejectsTimezoneSuffixWithHintToUseTimestamptz() {
        assertThatThrownBy(() -> SqlParameterTypes.convertValue(SqlParamType.timestamp, "2026-11-20T15:30:45Z", "ts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamptz");

        assertThatThrownBy(() -> SqlParameterTypes.convertValue(SqlParamType.timestamp, "2026-11-20T15:30:45+03:00", "ts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamptz");

        assertThatThrownBy(() -> SqlParameterTypes.convertValue(SqlParamType.timestamp, "2026-11-20 15:30:45-05:00", "ts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamptz");
    }

    @Test
    void timestamptzRejectsLocalTimestampWithoutOffset() {
        assertThatThrownBy(() -> SqlParameterTypes.convertValue(SqlParamType.timestamptz, "2026-11-20T15:30:45", "tz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void preservesTypedNullAcrossNamedParameterRewriterAndBindsSqlType() throws SQLException {
        Map<String, Object> namedParams = new HashMap<>();
        namedParams.put("d", null);
        namedParams.put("status", "OK");

        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(
                "SELECT * FROM t WHERE dt = :d AND status = :status",
                null,
                namedParams,
                List.of(new SqlParamTypeEntry("d", SqlParamType.date)));

        assertThat(binding.namedParams().get("d")).isEqualTo(new TypedNull(Types.DATE));
        assertThat(binding.namedParams().get("status")).isEqualTo("OK");

        NamedParameterRewriter.PreparedSql rewritten =
                NamedParameterRewriter.rewrite("SELECT * FROM t WHERE dt = :d AND status = :status", binding.namedParams());
        assertThat(rewritten.params()).containsExactly(new TypedNull(Types.DATE), "OK");

        PreparedStatement ps = mock(PreparedStatement.class);
        SqlParameterTypes.bind(ps, 1, rewritten.params().get(0));
        SqlParameterTypes.bind(ps, 2, rewritten.params().get(1));
        verify(ps).setNull(1, Types.DATE);
        verify(ps).setString(2, "OK");
    }

    @Test
    void preservesTypedNullInPositionalParams() throws SQLException {
        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(
                "SELECT * FROM t WHERE created_at >= ?",
                Arrays.asList((Object) null),
                null,
                List.of(new SqlParamTypeEntry("1", SqlParamType.timestamp)));

        assertThat(binding.params()).containsExactly(new TypedNull(Types.TIMESTAMP));

        PreparedStatement ps = mock(PreparedStatement.class);
        SqlParameterTypes.bind(ps, 1, binding.params().get(0));
        verify(ps).setNull(1, Types.TIMESTAMP);
    }

    @Test
    void bindsTypedObjectsAndFallsBackWhenOffsetDateTimeUnsupported() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        Date d = Date.valueOf(LocalDate.of(2026, 11, 20));
        Timestamp ts = Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 12, 0, 0));
        OffsetDateTime odt = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 0, ZoneOffset.UTC);
        UUID uuid = UUID.randomUUID();
        BigDecimal dec = new BigDecimal("10.50");

        SqlParameterTypes.bind(ps, 1, d);
        SqlParameterTypes.bind(ps, 2, ts);
        SqlParameterTypes.bind(ps, 3, uuid);
        SqlParameterTypes.bind(ps, 4, 42L);
        SqlParameterTypes.bind(ps, 5, dec);
        SqlParameterTypes.bind(ps, 6, Boolean.TRUE);
        SqlParameterTypes.bind(ps, 7, "text");
        SqlParameterTypes.bind(ps, 8, null);
        SqlParameterTypes.bind(ps, 9, odt);

        verify(ps).setDate(1, d);
        verify(ps).setTimestamp(2, ts);
        verify(ps).setObject(3, uuid);
        verify(ps).setLong(4, 42L);
        verify(ps).setBigDecimal(5, dec);
        verify(ps).setBoolean(6, true);
        verify(ps).setString(7, "text");
        verify(ps).setNull(8, Types.VARCHAR);
        verify(ps).setObject(9, odt);

        PreparedStatement legacyDriver = mock(PreparedStatement.class);
        doThrow(new SQLFeatureNotSupportedException("no OffsetDateTime")).when(legacyDriver).setObject(1, odt);
        SqlParameterTypes.bind(legacyDriver, 1, odt);
        verify(legacyDriver).setTimestamp(1, Timestamp.from(odt.toInstant()));
    }

    @Test
    void convertsListValuesForInClause() {
        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE event_date IN (:dates)",
                null,
                Map.of("dates", List.of("2026-11-20", "2026-11-21")),
                List.of(new SqlParamTypeEntry("dates", SqlParamType.date)));

        assertThat(binding.namedParams().get("dates")).isEqualTo(List.of(
                Date.valueOf("2026-11-20"),
                Date.valueOf("2026-11-21")));

        NamedParameterRewriter.PreparedSql rewritten = NamedParameterRewriter.rewrite(
                "SELECT * FROM events WHERE event_date IN (:dates)",
                binding.namedParams());

        assertThat(rewritten.sql()).isEqualTo("SELECT * FROM events WHERE event_date IN (?, ?)");
        assertThat(rewritten.params()).containsExactly(List.of(
                Date.valueOf("2026-11-20"),
                Date.valueOf("2026-11-21")));
    }

    @Test
    void convertsPositionalParamsEndToEnd() {
        SqlParameterBindingResolver.Binding binding = SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE created_at >= ? AND status = ?",
                List.of("2026-11-20T09:15:00", "PAID"),
                null,
                List.of(new SqlParamTypeEntry("1", SqlParamType.timestamp)));

        assertThat(binding.params()).containsExactly(
                Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 9, 15, 0)),
                "PAID");
    }

    @Test
    void rejectsNullBlankDuplicateAndInvalidParamTypeEntries() {
        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = :id",
                null,
                Map.of("id", "1"),
                Collections.singletonList((SqlParamTypeEntry) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = :id",
                null,
                Map.of("id", "1"),
                List.of(new SqlParamTypeEntry(" ", SqlParamType.integer))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = :id",
                null,
                Map.of("id", "1"),
                List.of(new SqlParamTypeEntry("id", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = :id",
                null,
                Map.of("id", "1"),
                List.of(
                        new SqlParamTypeEntry("id", SqlParamType.integer),
                        new SqlParamTypeEntry("id", SqlParamType.decimal))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate paramTypes entry for 'id'");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = ?",
                List.of("1"),
                null,
                List.of(new SqlParamTypeEntry("0", SqlParamType.integer))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = ?",
                List.of("1"),
                null,
                List.of(new SqlParamTypeEntry("2", SqlParamType.integer))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range 1..1");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = ?",
                List.of("1"),
                null,
                List.of(new SqlParamTypeEntry("first", SqlParamType.integer))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based integer index");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = :id",
                null,
                Map.of("id", "1"),
                List.of(new SqlParamTypeEntry(":id", SqlParamType.integer))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not start with ':'");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT * FROM events WHERE id = :id",
                null,
                Map.of("id", "1"),
                List.of(new SqlParamTypeEntry("other", SqlParamType.integer))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match any key");

        assertThatThrownBy(() -> SqlParameterBindingResolver.resolve(
                "SELECT 1",
                null,
                null,
                List.of(new SqlParamTypeEntry("1", SqlParamType.integer))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SQL contains no placeholders");
    }
}
