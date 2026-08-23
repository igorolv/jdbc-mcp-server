package ru.it_spectrum.ai.jdbc.mcp.resource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogResourceUrisTest {

    @Test
    void catalogIsEmbeddedAsAnEncodedNonTemplateSegment() {
        CatalogResourceUris uris = new CatalogResourceUris("orders/eu");

        assertThat(uris.manifest()).isEqualTo("jdbc-mcp://catalog/orders%2Feu/manifest");
        assertThat(uris.tableTemplate())
                .isEqualTo("jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}");
        assertThat(uris.table("Схема", "Odd/Table"))
                .isEqualTo("jdbc-mcp://catalog/orders%2Feu/schemas/%D0%A1%D1%85%D0%B5%D0%BC%D0%B0/tables/Odd%2FTable");
        assertThat(uris.columnTemplate())
                .isEqualTo("jdbc-mcp://catalog/orders%2Feu/schemas/{schema}/tables/{table}/columns/{column}");
    }

    @Test
    void parsesEncodedDatabaseIdentifiersWithoutTreatingThemAsPathSeparators() {
        CatalogResourceUris uris = new CatalogResourceUris("orders/eu");
        CatalogResourceUris.ColumnRef ref = uris.parseColumn(
                "jdbc-mcp://catalog/orders%2Feu/schemas/%D0%A1%D1%85%D0%B5%D0%BC%D0%B0/"
                        + "tables/Odd%2FTable/columns/amount%25gross");

        assertThat(ref.schema()).isEqualTo("Схема");
        assertThat(ref.table()).isEqualTo("Odd/Table");
        assertThat(ref.column()).isEqualTo("amount%gross");
    }

    @Test
    void rejectsAResourceBelongingToAnotherCatalog() {
        CatalogResourceUris uris = new CatalogResourceUris("orders");

        assertThatThrownBy(() -> uris.parseTable(
                "jdbc-mcp://catalog/billing/schemas/public/tables/invoice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another JDBC catalog");
    }
}
