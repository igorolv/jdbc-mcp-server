package ru.it_spectrum.ai.jdbc.mcp.usage;

import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;

import java.util.List;

/**
 * Produces canonical usage records for the runtime catalog.
 *
 * <p>Implementations may load records from local files or derive them from the connected
 * database metadata. The catalog indexer owns duplicate handling and persistence.
 */
public interface UsageCatalogSource {

    String name();

    List<QueryUsage> load() throws Exception;
}
