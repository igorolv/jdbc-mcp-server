package ru.it_spectrum.ai.jdbc.mcp.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageConfidence;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageFieldUsage;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageTransformation;
import ru.it_spectrum.ai.jdbc.mcp.usage.format.QueryUsageTransformationKind;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the JSON contract of {@link QueryUsage}. The same type is deserialised when usage JSON
 * files are scanned into the runtime catalog index.
 */
class QueryUsageJsonTest {

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
        QueryUsage req = mapper.readValue(json, QueryUsage.class);
        assertThat(req.schemaVersion()).isEqualTo(1);
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
        QueryUsage req = mapper.readValue(json, QueryUsage.class);
        QueryUsageFieldUsage fu = req.fieldUsages().get(0);
        assertThat(fu.transformation().kind()).isEqualTo(QueryUsageTransformationKind.IDENTITY);
        assertThat(fu.confidence()).isEqualTo(QueryUsageConfidence.HIGH);
    }

    @Test
    void canonicalEnumSerializationIsLowercase() throws Exception {
        String out = mapper.writeValueAsString(
                new QueryUsageTransformation(QueryUsageTransformationKind.AGGREGATE, null));
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
        assertThatThrownBy(() -> mapper.readValue(json, QueryUsage.class))
                .isInstanceOfAny(InvalidFormatException.class, IllegalArgumentException.class,
                        com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    void canonicalMinimalExampleDeserializes() throws Exception {
        QueryUsage req = readExample("query-usage-record.minimal.json");

        assertThat(req.schemaVersion()).isEqualTo(1);
        assertThat(req.dataSource()).isEqualTo("SHOP");
        assertThat(req.source().kind()).isEqualTo("manual");
        assertThat(req.source().path()).isEqualTo("examples/manual/customer-count");
        assertThat(req.sql()).contains("customer_count");
    }

    @Test
    void canonicalFullExampleDeserializes() throws Exception {
        QueryUsage req = readExample("query-usage-record.full.json");

        assertThat(req.schemaVersion()).isEqualTo(1);
        assertThat(req.source().kind()).isEqualTo("java-dao");
        assertThat(req.parameters()).hasSize(2);
        assertThat(req.outputs()).hasSize(2);
        assertThat(req.fieldUsages()).singleElement()
                .satisfies(usage -> {
                    assertThat(usage.output()).isEqualTo("customer_name");
                    assertThat(usage.transformation().kind())
                            .isEqualTo(QueryUsageTransformationKind.IDENTITY);
                    assertThat(usage.location().kind()).isEqualTo("ui-label");
                });
        assertThat(req.sourceMeta()).containsEntry("adapter", "example");
    }

    private QueryUsage readExample(String fileName) throws Exception {
        String json = Files.readString(Path.of("examples", "usage", fileName));
        return mapper.readValue(json, QueryUsage.class);
    }
}
