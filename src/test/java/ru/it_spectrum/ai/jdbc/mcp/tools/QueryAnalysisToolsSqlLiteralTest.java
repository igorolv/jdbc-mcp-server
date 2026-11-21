package ru.it_spectrum.ai.jdbc.mcp.tools;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlParameterTypes.TypedNull;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryAnalysisToolsSqlLiteralTest {

    @Test
    void formatsTypedValuesForSqlServerShowplan() {
        assertThat(QueryAnalysisTools.sqlLiteral(null)).isEqualTo("NULL");
        assertThat(QueryAnalysisTools.sqlLiteral(new TypedNull(Types.TIMESTAMP))).isEqualTo("NULL");
        assertThat(QueryAnalysisTools.sqlLiteral(LocalDate.of(2026, 11, 20)))
                .isEqualTo("CAST('2026-11-20' AS DATE)");
        assertThat(QueryAnalysisTools.sqlLiteral(LocalDateTime.of(2026, 11, 20, 15, 30, 45, 123_456_000)))
                .isEqualTo("CAST('2026-11-20T15:30:45.123456' AS DATETIME2)");
        assertThat(QueryAnalysisTools.sqlLiteral(Date.valueOf(LocalDate.of(2026, 11, 20))))
                .isEqualTo("CAST('2026-11-20' AS DATE)");
        assertThat(QueryAnalysisTools.sqlLiteral(Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 15, 30, 0))))
                .isEqualTo("CAST('2026-11-20T15:30' AS DATETIME2)");
        assertThat(QueryAnalysisTools.sqlLiteral(OffsetDateTime.of(2026, 11, 20, 15, 30, 0, 0, ZoneOffset.ofHours(3))))
                .isEqualTo("CAST('2026-11-20T15:30+03:00' AS DATETIMEOFFSET)");
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertThat(QueryAnalysisTools.sqlLiteral(uuid))
                .isEqualTo("CAST('123e4567-e89b-12d3-a456-426614174000' AS UNIQUEIDENTIFIER)");
    }
}
