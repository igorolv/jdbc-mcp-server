package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "KnownSourceKindsResult response payload.")
public record KnownSourceKindsResult(
        @Schema(description = "Kinds.", nullable = true)
        List<KindEntry> kinds
) {
    @Schema(description = "KindEntry response payload.")
    public record KindEntry(
            @Schema(description = "Kind.", nullable = true)
            String kind,
            @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int count
    ) {
    }
}
