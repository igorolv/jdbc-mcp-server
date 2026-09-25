package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.model.plan.PlanAnalysisSummary;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlitePlanParserTest {

    private static final String PLAN = """
            QUERY PLAN
            |--SEARCH t USING INDEX sqlite_autoindex_t_1 (name=?)
            |--LIST SUBQUERY 1
            |  |--SCAN o
            |  `--CREATE BLOOM FILTER
            `--SCAN events AS e USING COVERING INDEX idx_events_status
            """;

    private final SqlitePlanParser parser = new SqlitePlanParser();

    @Test
    void buildsTheTreeFromTheShellFormat() {
        ParsedPlan plan = parser.parse(rows(PLAN), false);

        assertThat(plan.engine()).isEqualTo("sqlite");
        PlanNode root = plan.root();
        assertThat(root.nodeType()).isEqualTo("QUERY PLAN");
        assertThat(root.children()).extracting(PlanNode::nodeType)
                .containsExactly("INDEX SEEK", "LIST SUBQUERY 1", "INDEX SCAN");

        PlanNode seek = root.children().get(0);
        assertThat(seek.relation()).isEqualTo("t");
        assertThat(seek.raw()).containsEntry("using", "USING INDEX sqlite_autoindex_t_1 (name=?)");

        PlanNode subquery = root.children().get(1);
        assertThat(subquery.children()).extracting(PlanNode::nodeType)
                .containsExactly("TABLE SCAN", "CREATE BLOOM FILTER");
        assertThat(subquery.children().getFirst().relation()).isEqualTo("o");

        PlanNode covering = root.children().get(2);
        assertThat(covering.relation()).isEqualTo("events");
        assertThat(covering.raw()).containsEntry("alias", "e");
    }

    @Test
    void acceptsTheLegacyScanTableWording() {
        PlanNode node = parser.parse(rows("QUERY PLAN\n`--SCAN TABLE orders AS o\n"), false).root();
        assertThat(node.nodeType()).isEqualTo("TABLE SCAN");
        assertThat(node.relation()).isEqualTo("orders");
    }

    @Test
    void analyzerReportsBothKindsOfFullScan() {
        PlanAnalysisSummary summary = PlanAnalyzer.summarize(parser.parse(rows(PLAN), false));
        assertThat(summary.fullScans()).extracting(s -> s.relation()).containsExactly("o", "events");
    }

    private static QueryResult rows(String plan) {
        return new QueryResult(List.of("PLAN"), List.of("VARCHAR"), List.of(Map.of("PLAN", plan)), false, 1);
    }
}
