package ru.it_spectrum.ai.jdbc.mcp.model.usage;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Empty result returned after usage-catalog references are re-resolved.")
public record ReresolveResult(
) {
}