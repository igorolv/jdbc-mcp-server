package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "KnownTagsResult response payload.")
public record KnownTagsResult(
        @Schema(description = "Tags.", nullable = true)
        List<TagEntry> tags
) {
    @Schema(description = "TagEntry response payload.")
    public record TagEntry(
            @Schema(description = "Tag.", nullable = true)
            String tag,
            @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int count
    ) {
    }
}