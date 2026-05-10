package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record KnownDomainsResult(
        List<DomainEntry> domains
) {
    public record DomainEntry(String domain, int count) {
    }
}