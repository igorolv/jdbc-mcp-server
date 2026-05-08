package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the JSON contract of {@link IngestPayload}. The same {@code Request} type is
 * deserialised both by the MCP runtime (when the {@code ingestQuery} tool is invoked) and by
 * any future bulk loader that reads JSONL/JSON files into the catalog — a single source of
 * truth for the payload shape.
 */
class IngestPayloadJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void minimalPayloadDeserializes() throws Exception {
        String json = """
                {
                  "dataSource": "SHOP",
                  "source": {"kind": "dao", "path": "Customer.java"},
                  "sql": "SELECT 1 FROM dual"
                }
                """;
        IngestPayload.Request req = mapper.readValue(json, IngestPayload.Request.class);
        assertThat(req.dataSource()).isEqualTo("SHOP");
        assertThat(req.source().unit()).isNull();
        assertThat(req.parameters()).isNull();
        assertThat(req.outputs()).isNull();
        assertThat(req.fieldUsages()).isNull();
    }

    @Test
    void enumsAcceptCanonicalLowercaseAndAreCaseInsensitive() throws Exception {
        String json = """
                {
                  "dataSource": "SHOP",
                  "source": {"kind": "dao", "path": "x.java"},
                  "sql": "SELECT 1",
                  "fieldUsages": [
                    {"businessObject": "X",
                     "transformation": {"kind": "Identity"},
                     "confidence": "HIGH"}
                  ]
                }
                """;
        IngestPayload.Request req = mapper.readValue(json, IngestPayload.Request.class);
        IngestPayload.FieldUsage fu = req.fieldUsages().get(0);
        assertThat(fu.transformation().kind()).isEqualTo(IngestPayload.TransformationKind.IDENTITY);
        assertThat(fu.confidence()).isEqualTo(IngestPayload.Confidence.HIGH);
    }

    @Test
    void canonicalEnumSerializationIsLowercase() throws Exception {
        String out = mapper.writeValueAsString(
                new IngestPayload.Transformation(IngestPayload.TransformationKind.AGGREGATE, null));
        assertThat(out).contains("\"kind\":\"aggregate\"");
    }

    @Test
    void unknownEnumValueIsRejected() {
        String json = """
                {
                  "dataSource": "SHOP",
                  "source": {"kind": "dao", "path": "x.java"},
                  "sql": "SELECT 1",
                  "fieldUsages": [
                    {"transformation": {"kind": "wat"}}
                  ]
                }
                """;
        assertThatThrownBy(() -> mapper.readValue(json, IngestPayload.Request.class))
                .isInstanceOfAny(InvalidFormatException.class, IllegalArgumentException.class,
                        com.fasterxml.jackson.databind.JsonMappingException.class);
    }
}
