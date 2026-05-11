package ru.it_spectrum.ai.jdbc.mcp.plan;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlServerPlanParserTest {

    @Test
    void parsesShowPlanXmlTree() {
        String xml = """
                <ShowPlanXML>
                  <BatchSequence>
                    <Batch>
                      <Statements>
                        <StmtSimple StatementText="SELECT * FROM dbo.customers"
                                    StatementType="SELECT"
                                    StatementSubTreeCost="1.5"
                                    StatementEstRows="100">
                          <QueryPlan>
                            <RelOp NodeId="0" PhysicalOp="Nested Loops" LogicalOp="Inner Join"
                                   EstimateRows="100" EstimatedTotalSubtreeCost="1.5">
                              <NestedLoops>
                                <OuterReferences />
                                <RelOp NodeId="1" PhysicalOp="Table Scan" LogicalOp="Table Scan"
                                       EstimateRows="10000" EstimatedTotalSubtreeCost="1.0">
                                  <TableScan>
                                    <Object Database="[db]" Schema="[dbo]" Table="[customers]" />
                                  </TableScan>
                                </RelOp>
                                <RelOp NodeId="2" PhysicalOp="Index Seek" LogicalOp="Index Seek"
                                       EstimateRows="1" EstimatedTotalSubtreeCost="0.1">
                                  <IndexScan>
                                    <Object Database="[db]" Schema="[dbo]" Table="[orders]" Index="[ix_orders_customer]" />
                                  </IndexScan>
                                </RelOp>
                              </NestedLoops>
                            </RelOp>
                          </QueryPlan>
                        </StmtSimple>
                      </Statements>
                    </Batch>
                  </BatchSequence>
                </ShowPlanXML>
                """;

        ParsedPlan parsed = new SqlServerPlanParser().parse(result(xml), false);

        assertThat(parsed.engine()).isEqualTo("mssql");
        assertThat(parsed.analyzed()).isFalse();
        assertThat(parsed.root().nodeType()).isEqualTo("SELECT");
        assertThat(parsed.root().estimatedRows()).isEqualTo(100L);
        assertThat(parsed.root().children()).hasSize(1);

        PlanNode join = parsed.root().children().getFirst();
        assertThat(join.nodeType()).isEqualTo("Nested Loops");
        assertThat(join.estimatedRows()).isEqualTo(100L);
        assertThat(join.children()).hasSize(2);
        assertThat(join.children().get(0).nodeType()).isEqualTo("Table Scan");
        assertThat(join.children().get(0).relation()).isEqualTo("dbo.customers");
        assertThat(join.children().get(1).nodeType()).isEqualTo("Index Seek");
        assertThat(join.children().get(1).relation()).isEqualTo("dbo.orders");
    }

    private QueryResult result(String xml) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("Microsoft SQL Server 2005 XML Showplan", xml);
        return new QueryResult(
                List.of("Microsoft SQL Server 2005 XML Showplan"),
                List.of("xml"),
                List.of(row),
                false,
                1);
    }
}
