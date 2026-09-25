package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenericDialectTest {

    @Test
    void mixedCaseNamesStayExactOnLowerCaseFoldingDatabases() throws Exception {
        GenericDialect dialect = dialect(false, true);

        assertThat(dialect.quoteIdentifier("MixedCase")).isEqualTo("\"MixedCase\"");
        assertThat(dialect.quoteIdentifier("lowercase")).isEqualTo("\"lowercase\"");
        assertThat(dialect.quoteIdentifier("UPPERCASE")).isEqualTo("UPPERCASE");
    }

    @Test
    void mixedCaseNamesStayExactOnUpperCaseFoldingDatabases() throws Exception {
        GenericDialect dialect = dialect(true, false);

        assertThat(dialect.quoteIdentifier("MixedCase")).isEqualTo("\"MixedCase\"");
        assertThat(dialect.quoteIdentifier("UPPERCASE")).isEqualTo("\"UPPERCASE\"");
        assertThat(dialect.quoteIdentifier("lowercase")).isEqualTo("lowercase");
    }

    private static GenericDialect dialect(boolean storesUpper, boolean storesLower) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(storesUpper);
        when(metadata.storesLowerCaseIdentifiers()).thenReturn(storesLower);
        return new GenericDialect(dataSource);
    }
}
