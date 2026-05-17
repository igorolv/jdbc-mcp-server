package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Known source-kind values discovered in the usage catalog.")
public record KnownSourceKindsResult(
        @Schema(description = "Known source-kind values and their usage counts.", nullable = true)
        List<KindEntry> kinds
) {
    @Schema(description = "Source kind and number of catalog queries that use it.")
    public record KindEntry(
            @Schema(description = "Source kind value used by catalog records, such as file, view, routine, or import.", nullable = true)
            String kind,
            @Schema(description = "Number of catalog queries associated with this source kind.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int count
    ) {
    }
}
