package ru.it_spectrum.ai.jdbc.mcp.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore.RoutineRecord;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore.SqlSupplier;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore.StructureSnapshotData;
import ru.it_spectrum.ai.jdbc.mcp.metadata.StructureSnapshotStore.ViewRecord;
import ru.it_spectrum.ai.jdbc.mcp.model.Opaque;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.CheckConstraint;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Column;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.ForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Index;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.IncomingForeignKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.PrimaryKey;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.RoutineEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SearchObjectEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.SequenceEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.Trigger;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.UniqueConstraint;
import ru.it_spectrum.ai.jdbc.mcp.usage.CatalogTestSupport;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteStructureSnapshotStoreTest {

    private static final ObjectMapper MAPPER = new JsonConfig().jdbcMcpObjectMapper();

    private SqliteStructureSnapshotStore store;

    private static <T> SqlSupplier<T> boom() {
        return () -> {
            throw new AssertionError("loader must not be called when the snapshot serves the value");
        };
    }

    private static TableDescription customer() {
        return new TableDescription(
                "public", "customer", "TABLE", "People we bill",
                List.of(
                        new Column("id", 1, "bigint", 19, null, false, null, "pk", Boolean.TRUE),
                        new Column("org_id", 2, "bigint", 19, null, true, null, null, null),
                        new Column("status", 3, "varchar", 16, null, true, "'NEW'", null, null)),
                new PrimaryKey("customer_pk", List.of("id")),
                List.of(new UniqueConstraint("customer_uq", List.of("org_id"))),
                List.of(new Index("customer_status_ix", false, List.of("status"))),
                List.of(new ForeignKey("customer_org_fk", List.of("org_id"),
                        "public", "org", List.of("id"))),
                List.of(Opaque.of(new IncomingForeignKey("order_customer_fk", "public", "orders",
                        List.of("customer_id"), List.of("id")))),
                List.of(new CheckConstraint("customer_status_chk", List.of("status"),
                        "status IN ('NEW','ACTIVE')", List.of("NEW", "ACTIVE"))),
                List.of());
    }

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = CatalogTestSupport.temporaryCatalog();
        store = new SqliteStructureSnapshotStore(ds, MAPPER);
    }

    @Test
    void describeTableRoundTripsThroughJson() throws Exception {
        TableDescription original = customer();
        store.saveAll(List.of(original));

        TableDescription back = store.peekDescribeTable("public", "customer");
        assertThat(back).isEqualTo(original);
    }

    @Test
    void describeTableLazyFillsThenServesForever() throws Exception {
        TableDescription original = customer();
        assertThat(store.peekDescribeTable("public", "customer")).isNull();

        // miss → loader runs once and the value is persisted
        TableDescription first = store.describeTable("public", "customer", () -> original);
        assertThat(first).isEqualTo(original);

        // hit → loader must never run again (cache forever, no TTL)
        TableDescription second = store.describeTable("public", "customer", boom());
        assertThat(second).isEqualTo(original);
    }

    @Test
    void listTablesIsScopeGated() throws Exception {
        // not covered yet → falls through to loader
        List<TableEntry> live = List.of(new TableEntry("public", "customer", "TABLE"));
        assertThat(store.listTables("public", "%", new String[]{"TABLE"}, () -> live))
                .isEqualTo(live);

        rebuildWithCustomer();

        List<TableEntry> fromSnapshot = store.listTables("public", "%", new String[]{"TABLE"}, boom());
        assertThat(fromSnapshot).extracting(TableEntry::name).containsExactly("customer");
        assertThat(fromSnapshot).extracting(TableEntry::type).containsExactly("TABLE");
    }

    @Test
    void objectMethodsServeFromSnapshotAfterRebuild() throws Exception {
        rebuildWithCustomer();

        assertThat(store.views("public", boom()))
                .extracting(row -> row.get("name")).containsExactly("v_active");
        assertThat(store.viewDefinition("public", "v_active", boom()))
                .isEqualTo("SELECT * FROM customer WHERE status = 'ACTIVE'");

        assertThat(store.routines("public", "%", boom()))
                .extracting(RoutineEntry::name).containsExactly("calc_total");
        assertThat(store.routineSource("public", "calc_total", boom()))
                .isEqualTo("BEGIN RETURN 1; END");

        List<Trigger> withBody = store.triggers("public", true, boom());
        assertThat(withBody).hasSize(1);
        assertThat(withBody.getFirst().name()).isEqualTo("trg_audit");
        assertThat(withBody.getFirst().events()).containsExactly("INSERT", "UPDATE");
        assertThat(withBody.getFirst().definition()).isEqualTo("audit body");

        // includeDefinition=false strips the body, matching live semantics
        assertThat(store.triggers("public", false, boom()).getFirst().definition()).isNull();
        assertThat(store.triggerDefinition("public", "customer", "trg_audit", boom()))
                .isEqualTo("audit body");

        assertThat(store.sequences("public", boom()))
                .extracting(SequenceEntry::name).containsExactly("customer_seq");
    }

    @Test
    void searchObjectsAndFindTablesDeriveFromSnapshot() throws Exception {
        // empty scope → loader
        assertThat(store.searchObjects("cust", () -> List.of(new SearchObjectEntry("x", "y", "TABLE"))))
                .extracting(SearchObjectEntry::name).containsExactly("y");
        assertThat(store.findTablesByName("customer", () -> List.of(new TableEntry("x", "customer", "TABLE"))))
                .extracting(TableEntry::schema).containsExactly("x");

        rebuildWithCustomer();

        assertThat(store.searchObjects("cust", boom()))
                .extracting(SearchObjectEntry::name).contains("customer", "customer_seq");
        assertThat(store.findTablesByName("customer", boom()))
                .extracting(TableEntry::schema).containsExactly("public");
        // case-insensitive name match
        assertThat(store.findTablesByName("CUSTOMER", boom())).hasSize(1);
    }

    @Test
    void invalidateAllFallsBackToLiveAndRebuildReplaces() throws Exception {
        rebuildWithCustomer();
        assertThat(store.sequences("public", boom())).hasSize(1);

        store.invalidateAll();

        // coverage is gone → loader is used again, snapshot rows cleared
        List<SequenceEntry> live = List.of(new SequenceEntry("public", "live_seq"));
        assertThat(store.sequences("public", () -> live)).isEqualTo(live);
        assertThat(store.peekDescribeTable("public", "customer")).isNull();
    }

    @Test
    void rebuildIsAtomicReplacement() throws Exception {
        rebuildWithCustomer();
        // second rebuild with empty scope replaces everything
        store.rebuild(new StructureSnapshotData(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));

        List<TableEntry> live = List.of(new TableEntry("public", "other", "TABLE"));
        assertThat(store.listTables("public", "%", new String[]{"TABLE"}, () -> live)).isEqualTo(live);
        assertThat(store.peekDescribeTable("public", "customer")).isNull();
    }

    private void rebuildWithCustomer() throws Exception {
        store.rebuild(new StructureSnapshotData(
                List.of("public"),
                List.of(customer()),
                List.of(new ViewRecord("public", "v_active",
                        "SELECT * FROM customer WHERE status = 'ACTIVE'")),
                List.of(new RoutineRecord("public", "calc_total", "FUNCTION", "BEGIN RETURN 1; END")),
                List.of(new Trigger("public", "customer", "trg_audit", "AFTER",
                        List.of("INSERT", "UPDATE"), true, "audit body")),
                List.of(new SequenceEntry("public", "customer_seq"))));
    }
}
