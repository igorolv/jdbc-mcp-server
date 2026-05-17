package ru.it_spectrum.ai.jdbc.mcp.model.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Column reference extracted from parsed SQL, with qualifier and source context.")
public record QueryColumnRef(
        @Schema(description = "Object name as reported by database metadata or parsed SQL.", nullable = true)
        String name,
        @Schema(description = "Qualifier used with the column reference, usually a table alias or table name.", nullable = true)
        String qualifier,
        @Schema(description = "Original SQL text fragment for this parsed item.", nullable = true)
        String text,
        @Schema(description = "SQL context where the column reference appears, such as select, where, join, order_by, or having.", nullable = true)
        String context
) {
}
