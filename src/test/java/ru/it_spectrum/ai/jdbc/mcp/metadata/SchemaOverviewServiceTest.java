package ru.it_spectrum.ai.jdbc.mcp.metadata;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.it_spectrum.ai.jdbc.mcp.model.context.SchemaOverview;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;
import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableEntry;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaOverviewServiceTest {

    @Test
    void schemaOverviewDescribesOnlySelectedTables() throws SQLException {
        MetadataService metadata = mock(MetadataService.class);
        List<TableEntry> listed = List.of(
                tableEntry("T1"),
                tableEntry("T2"),
                tableEntry("T3"));
        when(metadata.listTables(eq("APP"), eq("%"), any(String[].class))).thenReturn(listed);
        when(metadata.describeTables(eq("APP"), any())).thenReturn(Map.of(
                "app.t1", tableDescription("T1"),
                "app.t2", tableDescription("T2")));

        SchemaOverviewService service = new SchemaOverviewService(metadata, null, null, null, null);

        SchemaOverview overview = service.schemaOverview("APP", "%", true, false, false, 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TableEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(metadata).describeTables(eq("APP"), captor.capture());
        assertThat(captor.getValue()).extracting(TableEntry::name).containsExactly("T1", "T2");
        verify(metadata, never()).describeSchema("APP");
        assertThat(overview.tableCount()).isEqualTo(3);
        assertThat(overview.returnedTableCount()).isEqualTo(2);
        assertThat(overview.truncated()).isTrue();
    }

    private static TableEntry tableEntry(String name) {
        return new TableEntry("APP", name, "TABLE", null);
    }

    private static TableDescription tableDescription(String name) {
        return new TableDescription(
                "APP",
                name,
                "TABLE",
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of());
    }
}
