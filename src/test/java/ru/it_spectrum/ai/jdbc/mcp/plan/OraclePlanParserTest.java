package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OraclePlanParserTest {

    private Map<String, Object> row(long id, Long parentId, String op, String options,
                                    String object, Double cost, Long card) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ID", id);
        r.put("PARENT_ID", parentId);
        r.put("OPERATION", op);
        r.put("OPTIONS", options);
        r.put("OBJECT_NAME", object);
        r.put("COST", cost);
        r.put("CARDINALITY", card);
        r.put("FILTER_PREDICATES", null);
        r.put("ACCESS_PREDICATES", null);
        return r;
    }

    private QueryResult fromRows(List<Map<String, Object>> rows) {
        List<String> cols = List.of("ID", "PARENT_ID", "OPERATION", "OPTIONS",
                "OBJECT_NAME", "COST", "CARDINALITY",
                "FILTER_PREDICATES", "ACCESS_PREDICATES");
        return new QueryResult(cols, List.of("N","N","S","S","S","N","N","S","S"),
                rows, false, rows.size());
    }

    @Test
    void buildsParentChildTreeFromFlatRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(0, null, "SELECT STATEMENT", null, null, 200.0, 100L));
        rows.add(row(1, 0L, "HASH JOIN", null, null, 150.0, 100L));
        rows.add(row(2, 1L, "TABLE ACCESS", "FULL", "CUSTOMERS", 50.0, 50L));
        rows.add(row(3, 1L, "TABLE ACCESS", "BY INDEX ROWID", "ORDERS", 80.0, 1000L));

        ParsedPlan p = new OraclePlanParser().parse(fromRows(rows), false);

        assertThat(p.engine()).isEqualTo("oracle");
        assertThat(p.analyzed()).isFalse();
        PlanNode root = p.root();
        assertThat(root.nodeType()).isEqualTo("SELECT STATEMENT");
        assertThat(root.children()).hasSize(1);
        PlanNode hashJoin = root.children().get(0);
        assertThat(hashJoin.nodeType()).isEqualTo("HASH JOIN");
        assertThat(hashJoin.children()).hasSize(2);

        PlanNode seq = hashJoin.children().stream()
                .filter(c -> "CUSTOMERS".equals(c.relation())).findFirst().orElseThrow();
        assertThat(seq.nodeType()).isEqualTo("TABLE ACCESS FULL");
    }

    @Test
    void tolerantToMissingParentLink() {
        // Simulates a row with an unresolved PARENT_ID — we still return something sane (first
        // node as root).
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(5, null, "SELECT STATEMENT", null, null, 1.0, 1L));
        ParsedPlan p = new OraclePlanParser().parse(fromRows(rows), false);
        assertThat(p.root()).isNotNull();
        assertThat(p.root().nodeType()).isEqualTo("SELECT STATEMENT");
    }
}
