package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresPlanParserTest {

    private static final String PG_ANALYZE_JSON = """
            [
              {
                "Plan": {
                  "Node Type": "Hash Join",
                  "Startup Cost": 10.0,
                  "Total Cost": 150.5,
                  "Plan Rows": 1000,
                  "Actual Rows": 1250,
                  "Actual Loops": 1,
                  "Actual Total Time": 12.3,
                  "Plans": [
                    {
                      "Node Type": "Seq Scan",
                      "Relation Name": "customers",
                      "Total Cost": 50.0,
                      "Plan Rows": 500,
                      "Actual Rows": 500,
                      "Actual Loops": 1,
                      "Actual Total Time": 4.0
                    },
                    {
                      "Node Type": "Hash",
                      "Plans": [
                        {
                          "Node Type": "Index Scan",
                          "Relation Name": "orders",
                          "Index Name": "idx_orders_customer",
                          "Total Cost": 25.0,
                          "Plan Rows": 1000,
                          "Actual Rows": 2500,
                          "Actual Loops": 1,
                          "Actual Total Time": 3.1
                        }
                      ]
                    }
                  ]
                },
                "Planning Time": 0.25,
                "Execution Time": 13.9
              }
            ]
            """;

    private QueryResult wrap(String json) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("QUERY PLAN", json);
        return new QueryResult(List.of("QUERY PLAN"), List.of("text"),
                List.of(row), false, 1);
    }

    @Test
    void parsesAnalyzedPlanWithChildren() {
        PostgresPlanParser parser = new PostgresPlanParser(new ObjectMapper());
        ParsedPlan p = parser.parse(wrap(PG_ANALYZE_JSON), true);

        assertThat(p.engine()).isEqualTo("postgresql");
        assertThat(p.analyzed()).isTrue();
        assertThat(p.planningTimeMs()).isEqualTo(0.25);
        assertThat(p.executionTimeMs()).isEqualTo(13.9);

        PlanNode root = p.root();
        assertThat(root.nodeType()).isEqualTo("Hash Join");
        assertThat(root.totalCost()).isEqualTo(150.5);
        assertThat(root.actualRows()).isEqualTo(1250);
        assertThat(root.children()).hasSize(2);

        PlanNode seqScan = root.children().get(0);
        assertThat(seqScan.nodeType()).isEqualTo("Seq Scan");
        assertThat(seqScan.relation()).isEqualTo("customers");

        // Index Scan is the grandchild (wrapped in Hash).
        PlanNode idxScan = root.children().get(1).children().get(0);
        assertThat(idxScan.relation()).isEqualTo("orders");
    }

    @Test
    void failsOnEmptyResult() {
        QueryResult empty = new QueryResult(List.of("QUERY PLAN"), List.of("text"),
                List.of(), false, 0);
        assertThatThrownBy(() -> new PostgresPlanParser(new ObjectMapper()).parse(empty, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
