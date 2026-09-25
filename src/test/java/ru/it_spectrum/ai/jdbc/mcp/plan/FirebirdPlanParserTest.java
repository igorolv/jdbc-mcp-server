package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.model.plan.PlanAnalysisSummary;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirebirdPlanParserTest {

    /** Captured from Firebird 3.0.14 through Jaybird 6 for a join over a legacy address database. */
    private static final String JOIN_PLAN = """
            Select Expression
                -> Nested Loop Join (inner)
                    -> Table "FIAS_ADDROBJ" as "A" Full Scan
                    -> Filter
                        -> Table "FIAS_HOUSE" as "H" Access By ID
                            -> Bitmap
                                -> Index "I_FIAS_HOUSE_AOGUID" Range Scan (full match)
            """;

    private final FirebirdPlanParser parser = new FirebirdPlanParser();

    @Test
    void buildsTheTreeFromIndentation() {
        ParsedPlan plan = parser.parse(rows(JOIN_PLAN), false);

        assertThat(plan.engine()).isEqualTo("firebird");
        assertThat(plan.analyzed()).isFalse();
        PlanNode root = plan.root();
        assertThat(root.nodeType()).isEqualTo("Select Expression");
        PlanNode join = root.children().getFirst();
        assertThat(join.nodeType()).isEqualTo("Nested Loop Join (inner)");
        assertThat(join.children()).hasSize(2);

        PlanNode scan = join.children().get(0);
        assertThat(scan.nodeType()).isEqualTo("Full Scan");
        assertThat(scan.relation()).isEqualTo("FIAS_ADDROBJ");
        assertThat(scan.raw()).containsEntry("alias", "A");
        assertThat(scan.estimatedRows()).isNull();

        PlanNode access = join.children().get(1).children().getFirst();
        assertThat(access.nodeType()).isEqualTo("Access By ID");
        assertThat(access.relation()).isEqualTo("FIAS_HOUSE");
        PlanNode index = access.children().getFirst().children().getFirst();
        assertThat(index.nodeType()).isEqualTo("Index Range Scan (full match)");
        assertThat(index.raw()).containsEntry("index", "I_FIAS_HOUSE_AOGUID");
    }

    @Test
    void analyzerFlagsFullScansWithoutEstimates() {
        PlanAnalysisSummary summary = PlanAnalyzer.summarize(parser.parse(rows(JOIN_PLAN), false));
        assertThat(summary.nodeCount()).isEqualTo(7);
        assertThat(summary.fullScans()).hasSize(1);
        assertThat(summary.fullScans().getFirst().relation()).isEqualTo("FIAS_ADDROBJ");
        assertThat(summary.fullScans().getFirst().reason()).contains("no row estimate");
    }

    @Test
    void severalTopLevelExpressionsShareASyntheticRoot() {
        ParsedPlan plan = parser.parse(rows("""
                Sub-query
                    -> Singularity Check
                        -> Table "RDB$DATABASE" Full Scan
                Select Expression
                    -> Table "CUSTOMERS" Full Scan
                """), false);
        assertThat(plan.root().nodeType()).isEqualTo("Plan");
        assertThat(plan.root().children()).extracting(PlanNode::nodeType)
                .containsExactly("Sub-query", "Select Expression");
    }

    @Test
    void legacyOneLinePlanBecomesOneNode() {
        ParsedPlan plan = parser.parse(rows("PLAN JOIN (A NATURAL, H INDEX (I_FIAS_HOUSE_AOGUID))"), false);
        assertThat(plan.root().nodeType()).isEqualTo("PLAN JOIN (A NATURAL, H INDEX (I_FIAS_HOUSE_AOGUID))");
        assertThat(plan.root().children()).isEmpty();
    }

    @Test
    void rejectsAnEmptyPlan() {
        assertThatThrownBy(() -> parser.parse(rows(" "), false)).isInstanceOf(IllegalArgumentException.class);
    }

    private static QueryResult rows(String plan) {
        return new QueryResult(List.of("PLAN"), List.of("VARCHAR"), List.of(Map.of("PLAN", plan)), false, 1);
    }
}
