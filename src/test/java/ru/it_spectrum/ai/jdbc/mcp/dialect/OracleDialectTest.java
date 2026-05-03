package ru.it_spectrum.ai.jdbc.mcp.dialect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OracleDialectTest {

    private final OracleDialect dialect = new OracleDialect();

    @Test
    void buildExplainScopesPlanWithStatementId() {
        String sql = dialect.buildExplain("SELECT * FROM customers", false, "JDBC_MCP_abc");

        assertThat(sql)
                .startsWith("EXPLAIN PLAN SET STATEMENT_ID = 'JDBC_MCP_abc' FOR")
                .contains("SELECT * FROM customers");
    }

    @Test
    void buildExplainEscapesStatementIdLiteral() {
        String sql = dialect.buildExplain("SELECT 1", false, "id'quote");

        assertThat(sql).contains("STATEMENT_ID = 'id''quote'");
    }

    @Test
    void displayQueriesUseStatementIdBind() {
        assertThat(dialect.explainDisplayQuery("JDBC_MCP_abc"))
                .contains("DBMS_XPLAN.DISPLAY(NULL, ?, 'ALL')");
        assertThat(dialect.structuredPlanQuery("JDBC_MCP_abc"))
                .contains("WHERE statement_id = ?");
    }
}
