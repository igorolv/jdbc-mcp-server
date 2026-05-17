package ru.it_spectrum.ai.jdbc.mcp.model.context;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "CycleHint response payload.")
public record CycleHint(
        @Schema(description = "Tables.", nullable = true)
        List<String> tables,
        @Schema(description = "Note.", nullable = true)
        String note
) {
}
