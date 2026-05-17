package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "List of tables and views returned by listTables.")
public record ListTablesResult(
        @Schema(description = "Table and view entries.", nullable = true)
        List<TableEntry> tables
) {
}
