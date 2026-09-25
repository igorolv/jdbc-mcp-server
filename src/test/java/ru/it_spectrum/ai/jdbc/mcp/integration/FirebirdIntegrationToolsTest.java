package ru.it_spectrum.ai.jdbc.mcp.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The tool layer against Firebird 3: one logical schema, catalog queries over {@code RDB$}
 * tables, driver-captured plans and exact-count fallbacks. Unquoted identifiers are stored in
 * upper case, so the tests address objects that way — as a Firebird user would.
 */
@Tag("integration")
class FirebirdIntegrationToolsTest extends AbstractFirebirdToolsIntegrationTest {

    // ---------------- schemas and tables ----------------

    @Test
    void presentsTheDatabaseAsOneLogicalSchema() {
        assertThat(textValues(array(metadataTools().listSchemas(connection(), false).schemas())))
                .containsExactly("PUBLIC");

        ArrayNode tables = array(metadataTools().listTables(connection(), null, "%", null).tables());
        ObjectNode customers = (ObjectNode) findByField(tables, "name", "CUSTOMERS");
        assertThat(customers).isNotNull();
        assertThat(field(customers, "schema").asText()).isEqualTo("PUBLIC");
        assertThat(findByField(tables, "name", "V_CUSTOMER_TOTALS")).isNotNull();

        ArrayNode sameTables = array(metadataTools().listTables(connection(), "public", "%", null).tables());
        assertThat(sameTables.size()).isEqualTo(tables.size());
    }

    @Test
    void rejectsSchemasOtherThanTheLogicalOne() {
        assertInvalidArgument(() -> metadataTools().listTables(connection(), "dbo", "%", null), "no schemas");
    }

    @Test
    void describesKeysConstraintsCommentsAndTriggers() {
        ObjectNode orders = object(metadataTools().describeTable(connection(), null, "ORDERS"));
        assertThat(field(orders, "schema").asText()).isEqualTo("PUBLIC");
        assertThat(findByField((ArrayNode) field(orders, "columns"), "name", "CUSTOMER_ID")).isNotNull();
        assertThat(textValues((ArrayNode) field(field(orders, "primaryKey"), "columns"))).containsExactly("ID");

        ObjectNode fk = (ObjectNode) findByField((ArrayNode) field(orders, "foreignKeys"), "name", "ORDERS_CUSTOMER_FK");
        assertThat(fk).isNotNull();
        assertThat(field(fk, "referencedSchema").asText()).isEqualTo("PUBLIC");
        assertThat(field(fk, "referencedTable").asText()).isEqualTo("CUSTOMERS");
        assertThat(textValues((ArrayNode) field(fk, "referencedColumns"))).containsExactly("ID");

        ObjectNode check = (ObjectNode) findByField((ArrayNode) field(orders, "checkConstraints"),
                "name", "ORDERS_TOTAL_NONNEGATIVE");
        assertThat(check).isNotNull();
        assertThat(field(check, "definition").asText()).containsIgnoringCase("total >= 0");

        ObjectNode events = object(metadataTools().describeTable(connection(), null, "EVENTS"));
        ObjectNode statusCheck = (ObjectNode) findByField((ArrayNode) field(events, "checkConstraints"),
                "name", "EVENTS_STATUS_CHECK");
        assertThat(statusCheck).isNotNull();
        assertThat(field(statusCheck, "definition").asText()).contains("'OK'").contains("'FAIL'");

        ObjectNode customers = object(metadataTools().describeTable(connection(), null, "CUSTOMERS"));
        assertThat(field(customers, "remarks").asText()).isEqualTo("Customer master data");
        assertThat(field(findByField((ArrayNode) field(customers, "columns"), "name", "NAME"), "remarks").asText())
                .isEqualTo("Display name");
        assertThat(findByField((ArrayNode) field(customers, "referencedBy"), "fromTable", "ORDERS")).isNotNull();

        ObjectNode notes = object(metadataTools().describeTable(connection(), null, "CUSTOMER_NOTES"));
        ObjectNode trigger = (ObjectNode) findByField((ArrayNode) field(notes, "triggers"),
                "name", "CUSTOMER_NOTES_TOUCH_TRG");
        assertThat(trigger).isNotNull();
        assertThat(field(trigger, "timing").asText()).isEqualTo("BEFORE");
        assertThat(textValues((ArrayNode) field(trigger, "events"))).containsExactly("INSERT", "UPDATE");
        assertThat(field(trigger, "enabled").asBoolean()).isTrue();

        String definition = metadataTools().getTriggerDefinition(connection(),
                null, "CUSTOMER_NOTES", "CUSTOMER_NOTES_TOUCH_TRG");
        assertThat(definition).contains("TRIM(NEW.note)");
    }

    @Test
    void readsViewsRoutinesSequencesAndSearch() {
        assertThat(metadataTools().getViewDefinition(connection(), null, "V_CUSTOMER_TOTALS"))
                .containsIgnoringCase("FROM customers c");

        ArrayNode routines = array(metadataTools().listRoutines(connection(), null, "customer_count%").routines());
        ObjectNode routine = (ObjectNode) findByField(routines, "name", "CUSTOMER_COUNT_PROC");
        assertThat(routine).isNotNull();
        assertThat(field(routine, "type").asText()).isEqualTo("PROCEDURE");
        assertThat(metadataTools().getRoutineDefinition(connection(), null, "CUSTOMER_COUNT_PROC"))
                .contains("COUNT(*)");

        ArrayNode sequences = array(metadataTools().listSequences(connection(), null).sequences());
        assertThat(findByField(sequences, "name", "AUDIT_SEQ")).isNotNull();

        ArrayNode search = array(metadataTools().searchObjects(connection(), "customer").objects());
        assertThat(findByField(search, "name", "CUSTOMERS")).isNotNull();
        assertThat(findByField(search, "name", "V_CUSTOMER_TOTALS")).isNotNull();
        assertThat(findByField(search, "name", "CUSTOMER_COUNT_PROC")).isNotNull();
    }

    // ---------------- queries ----------------

    @Test
    void executesQueriesAndDecodesWin1251Text() {
        ObjectNode limited = object(queryTools().executeQuery(connection(),
                "SELECT name, email FROM customers ORDER BY id", null, null, 1, 5));
        assertThat(field(limited, "rowCount").asInt()).isEqualTo(1);
        assertThat(field(limited, "truncated").asBoolean()).isTrue();

        ObjectNode cyrillic = object(queryTools().executeQuery(connection(),
                "SELECT name FROM customers WHERE id = :id", null, Map.of("id", 2), null, 5));
        assertThat(field(row(cyrillic, 0), "NAME").asText()).isEqualTo("Боб");

        ObjectNode sample = object(sampleTools().sampleRows(connection(), null, "CUSTOMER_NOTES", 1));
        assertThat(field(sample, "rowCount").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsWritesInTheGuardAndOnTheServer() throws SQLException {
        assertRejected(() ->
                queryTools().executeQuery(connection(), "DELETE FROM customers", null, null, null, null),
                "Only SELECT");

        try (Connection connection = pool().getConnection();
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM customer_notes"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("read-only transaction");
        }
    }

    @Test
    void plansComeFromTheDriverAndFlagFullScans() {
        String plan = queryAnalysisTools().explainQuery(connection(),
                "SELECT o.total FROM orders o JOIN customers c ON c.id = o.customer_id WHERE c.name = :name",
                null, Map.of("name", "Alice"), false);
        assertThat(plan).contains("Select Expression").contains("\"ORDERS\"").contains("\"CUSTOMERS\"");

        ObjectNode summary = object(queryAnalysisTools().analyzePlan(connection(),
                "SELECT * FROM events WHERE amount > 10", null, null, false));
        assertThat(field(summary, "engine").asText()).isEqualTo("firebird");
        assertThat(field(summary, "nodeCount").asInt()).isGreaterThan(1);
        assertThat(field(summary, "fullScans").toString()).contains("EVENTS");

        ObjectNode valid = object(queryAnalysisTools().validateQuery(connection(),
                "SELECT FIRST 5 * FROM customers", null, null));
        assertThat(field(valid, "valid").asBoolean()).isTrue();
    }

    // ---------------- statistics ----------------

    @Test
    void statsComeFromIndexSelectivity() {
        ObjectNode table = object(statsTools().tableStats(connection(), null, "CUSTOMERS"));
        assertThat(field(table, "found").asBoolean()).isTrue();
        assertThat(field(table, "estimatedRows").asLong()).isEqualTo(2L);

        ArrayNode indexes = (ArrayNode) field(object(statsTools().indexStats(connection(), null, "CUSTOMERS")), "indexes");
        ObjectNode byName = (ObjectNode) findByField(indexes, "indexName", "IDX_CUSTOMERS_NAME");
        assertThat(byName).isNotNull();
        assertThat(textValues((ArrayNode) field(byName, "columns"))).containsExactly("NAME");

        ObjectNode redundant = object(statsTools().redundantIndexes(connection(), null, "LINE_ITEMS"));
        ObjectNode finding = (ObjectNode) findByField((ArrayNode) field(redundant, "findings"),
                "shadowedIndex", "IDX_LI_ORDER");
        assertThat(finding).isNotNull();
        assertThat(field(finding, "coveredByIndex").asText()).isEqualTo("IDX_LI_ORDER_SKU");

        // Firebird backs every foreign key with an index of its own.
        ObjectNode fkCoverage = object(statsTools().fkIndexCoverage(connection(), null, "ORDERS"));
        assertThat(field(fkCoverage, "uncoveredCount").asInt()).isZero();

        ObjectNode unused = object(statsTools().unusedIndexes(connection(), null, null));
        assertThat(field(unused, "supported").asBoolean()).isFalse();
        assertThat(field(unused, "note").asText()).contains("Firebird");
    }

    @Test
    void distributionToolsWorkWithoutPercentileAggregates() {
        ObjectNode stats = object(distributionTools().columnStats(connection(), null, "ORDERS", "TOTAL"));
        assertThat(field(stats, "totalRows").asInt()).isEqualTo(3);
        assertThat(field(stats, "distinctValues").asInt()).isEqualTo(3);

        ObjectNode distribution = object(distributionTools().columnDistribution(connection(), null, "EVENTS", "STATUS", 5));
        ObjectNode ok = (ObjectNode) findByField((ArrayNode) field(distribution, "values"), "value", "OK");
        assertThat(ok).isNotNull();
        assertThat(field(ok, "frequency").asInt()).isEqualTo(90);

        ObjectNode histogram = object(distributionTools().columnHistogram(connection(), null, "EVENTS", "AMOUNT"));
        assertThat(field(histogram, "percentileFunction").asText()).isEqualTo("percentile_disc");
        assertThat(field(histogram, "totalRows").asInt()).isEqualTo(100);
        // Ranks 1..10 are the FAIL rows (0.1 .. 1.0), rank 50 is OK row 40 (40 * 1.5).
        assertThat(field(histogram, "p50").asDouble()).isCloseTo(60.0, within(1e-9));
        assertThat(field(histogram, "min").asDouble()).isCloseTo(0.1, within(1e-9));

        ObjectNode nullRatio = object(distributionTools().nullRatio(connection(), null, "EVENTS"));
        ObjectNode category = (ObjectNode) findByField((ArrayNode) field(nullRatio, "columns"), "column", "CATEGORY");
        assertThat(field(category, "nullRows").asInt()).isEqualTo(10);
    }

    @Test
    void selectivityAndJoinCardinalityFallBackToExactCounts() {
        ObjectNode selectivity = object(distributionTools().estimateSelectivity(connection(),
                null, "EVENTS", "status = 'FAIL'"));
        assertThat(field(selectivity, "estimatedRows").asLong()).isEqualTo(10L);
        assertThat(field(selectivity, "baselineRows").asLong()).isEqualTo(100L);
        assertThat(field(selectivity, "selectivity").asDouble()).isCloseTo(0.1, within(1e-9));
        assertThat(field(selectivity, "note").asText()).startsWith("Exact counts");

        ObjectNode join = object(distributionTools().joinCardinality(connection(),
                null, "CUSTOMERS", "ID", null, "ORDERS", "CUSTOMER_ID", "INNER"));
        assertThat(field(join, "estimatedRows").asLong()).isEqualTo(3L);
    }

    // ---------------- schema context ----------------

    @Test
    void schemaContextFollowsForeignKeysInTheLogicalSchema() {
        ObjectNode context = object(schemaContextTools().tableContext(connection(), null, "ORDERS",
                1, true, false, false));
        assertThat(field(context, "rootSchema").asText()).isEqualTo("PUBLIC");
        assertThat(field(context, "tables").toString()).contains("CUSTOMERS");

        ObjectNode paths = object(schemaContextTools().findJoinPaths(connection(),
                null, "ORDERS", null, "CUSTOMERS", null, null, null, false));
        assertThat(((ArrayNode) field(paths, "paths")).size()).isGreaterThan(0);

        String brief = schemaContextTools().schemaBrief(connection(), null, null, null);
        assertThat(brief).contains("CUSTOMERS").contains("ORDERS");
    }

    @Test
    void anUnknownTableIsNotFoundRatherThanAnEmptyDescription() {
        assertUnknownTableIsNotFound("ORDERS", "NO_SUCH_TABLE");
    }
}
