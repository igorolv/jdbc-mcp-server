package ru.it_spectrum.ai.jdbc.mcp.model.resource;

import ru.it_spectrum.ai.jdbc.mcp.model.metadata.TableDescription;

/**
 * Catalog-qualified representation of one table or view description.
 */
public record TableResourceDocument(
        int resourceSchemaVersion,
        String catalog,
        TableDescription table
) {
}
