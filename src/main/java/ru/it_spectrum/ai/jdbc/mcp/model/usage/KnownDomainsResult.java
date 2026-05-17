package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "KnownDomainsResult response payload.")
public record KnownDomainsResult(
        @Schema(description = "Domains.", nullable = true)
        List<DomainEntry> domains
) {
    @Schema(description = "DomainEntry response payload.")
    public record DomainEntry(
            @Schema(description = "Domain.", nullable = true)
            String domain,
            @Schema(description = "Count.", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            int count
    ) {
    }
}