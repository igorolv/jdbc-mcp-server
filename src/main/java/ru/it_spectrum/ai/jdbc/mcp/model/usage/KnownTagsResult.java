package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Known business tags discovered in the usage catalog.")
public record KnownTagsResult(
        @Schema(description = "Business tags attached to the usage-catalog query.", nullable = true)
        List<TagEntry> tags
) {
    @Schema(description = "Business tag and number of catalog queries tagged with it.")
    public record TagEntry(
            @Schema(description = "Business tag value.", nullable = true)
            String tag,
            @Schema(description = "Number of catalog queries associated with this tag.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int count
    ) {
    }
}
