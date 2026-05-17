package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Known business domains discovered in the usage catalog.")
public record KnownDomainsResult(
        @Schema(description = "Known business domains and their usage counts.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<DomainEntry> domains
) {
    @Schema(description = "Business domain and number of catalog queries tagged with it.")
    public record DomainEntry(
            @Schema(description = "Business domain value.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String domain,
            @Schema(description = "Number of catalog queries associated with this domain.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int count
    ) {
    }
}
