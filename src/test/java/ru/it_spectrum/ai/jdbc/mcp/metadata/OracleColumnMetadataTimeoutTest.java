package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.it_spectrum.ai.jdbc.mcp.config.JdbcProperties;
import ru.it_spectrum.ai.jdbc.mcp.dialect.OracleDialect;
import ru.it_spectrum.ai.jdbc.mcp.sql.SqlExecutor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OracleColumnMetadataTimeoutTest {

    @Test
    void bulkColumnQueryUsesCallerProvidedTimeout() throws Exception {
        JdbcProperties properties = new JdbcProperties(
                "jdbc:oracle:thin:@//localhost:1521/test", "user", "pw", "SSV",
                30, 1_000, 100, "strict", 40, 0, 10_000, 5_000, 60_000);
        MetadataService service = new MetadataService(
                mock(SqlExecutor.class), new OracleDialect(), properties,
                mock(StructureSnapshotStore.class));

        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString(), eq(ResultSet.TYPE_FORWARD_ONLY),
                eq(ResultSet.CONCUR_READ_ONLY))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);

        assertThat(service.fetchOracleColumnsForTables(
                connection, "SSV", Set.of("ORDERS"), 300)).isEmpty();

        verify(statement).setQueryTimeout(300);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture(),
                eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY));
        assertThat(sql.getValue())
                .containsIgnoringCase("from all_tab_columns where owner =")
                .contains("DBMS_ASSERT.ENQUOTE_LITERAL(REPLACE(c.owner")
                .doesNotContainIgnoringCase("from user_tab_columns");
    }

}
