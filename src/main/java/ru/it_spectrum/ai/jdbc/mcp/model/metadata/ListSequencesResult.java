package ru.it_spectrum.ai.jdbc.mcp.model.metadata;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "List of sequences returned by listSequences.")
public record ListSequencesResult(
        @Schema(description = "Sequence entries.", nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<SequenceEntry> sequences
) {
}
