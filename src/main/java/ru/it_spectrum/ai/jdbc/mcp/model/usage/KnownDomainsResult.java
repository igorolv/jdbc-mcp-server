package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import java.util.List;

public record KnownDomainsResult(
        String dataSource,
        List<DomainEntry> domains
) {
    public record DomainEntry(String domain, int count) {
    }
}