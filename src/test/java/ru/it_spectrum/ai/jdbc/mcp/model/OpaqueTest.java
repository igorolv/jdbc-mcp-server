package ru.it_spectrum.ai.jdbc.mcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import ru.it_spectrum.ai.jdbc.mcp.config.JsonConfig;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@link Opaque} contract: an {@code Opaque<T>} field collapses to an unconstrained
 * schema (keeping its description) yet serializes its payload fully and unwrapped, is omitted
 * entirely when null under the NON_NULL server mapper, and round-trips back to a typed value
 * through the Jackson 2 store mapper (so it is safe on persisted types).
 */
class OpaqueTest {

    @Schema(description = "Rich payload that must NOT appear expanded in the schema.")
    record Rich(
            @Schema(description = "first") String a,
            @Schema(description = "second") int b) {}

    record Holder(
            @Schema(description = "opaque blob", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Opaque<Rich> payload,
            @Schema(description = "kept", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String kept) {}

    record ListHolder(
            @Schema(description = "opaque list", nullable = true,
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            List<Opaque<Rich>> items) {}

    private static final JsonMapper SERVER_MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .addModules(JacksonUtils.instantiateAvailableModules())
            .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void schemaCollapsesButKeepsDescriptionAndStaysOptional() {
        String schema = JsonSchemaGenerator.generateForType(Holder.class);

        // The opaque field's description survives...
        assertThat(schema).contains("opaque blob");
        // ...but Rich's own fields never expand into the schema.
        assertThat(schema).doesNotContain("\"first\"").doesNotContain("\"second\"");
        // It must not be required (null payloads are stripped by NON_NULL).
        assertThat(schema).doesNotMatch("(?s).*\"required\"\\s*:\\s*\\[[^\\]]*\"payload\"[^\\]]*\\].*");
    }

    @Test
    void serializesPayloadFullyAndUnwrapped() {
        String json = SERVER_MAPPER.writeValueAsString(new Holder(Opaque.of(new Rich("x", 7)), "k"));
        assertThat(json).isEqualTo("{\"payload\":{\"a\":\"x\",\"b\":7},\"kept\":\"k\"}");
    }

    @Test
    void nullPayloadIsOmitted() {
        assertThat(Opaque.of(null)).isNull();
        String json = SERVER_MAPPER.writeValueAsString(new Holder(Opaque.of(null), "k"));
        assertThat(json).isEqualTo("{\"kept\":\"k\"}");
    }

    @Test
    void roundTripsToTypedValueThroughStoreMapper() throws Exception {
        // The Jackson 2 application mapper that the snapshot store uses to persist + reload.
        var store = new JsonConfig().jdbcMcpObjectMapper();

        Holder one = new Holder(Opaque.of(new Rich("x", 7)), "k");
        Holder backOne = store.readValue(store.writeValueAsString(one), Holder.class);
        // Reconstructed value must be a real typed Rich, not a raw Map.
        Rich rich = backOne.payload().unwrap();
        assertThat(rich).isEqualTo(new Rich("x", 7));

        ListHolder many = new ListHolder(List.of(Opaque.of(new Rich("y", 9)), Opaque.of(new Rich("z", 11))));
        ListHolder backMany = store.readValue(store.writeValueAsString(many), ListHolder.class);
        assertThat(backMany.items()).hasSize(2);
        assertThat(backMany.items().get(0).unwrap()).isEqualTo(new Rich("y", 9));
        assertThat(backMany.items().get(1).unwrap()).isEqualTo(new Rich("z", 11));
    }
}
