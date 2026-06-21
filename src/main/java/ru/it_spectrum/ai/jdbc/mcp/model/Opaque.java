package ru.it_spectrum.ai.jdbc.mcp.model;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;

import java.io.IOException;
import java.util.Objects;

/**
 * Type-safe wrapper that deliberately erases its element's JSON schema while keeping the value
 * intact through serialization and deserialization.
 *
 * <p>Spring AI's {@code JsonSchemaGenerator} renders an {@code Opaque<T>} field as an unconstrained
 * {@code {"type": "object"}} (the field's {@code @Schema} description is still emitted), because the
 * wrapper stores its payload as {@code Object} and the generator finds no structure to expand.
 * Jackson serializes the wrapped value fully and unwrapped via {@link JsonValue} — on the wire the
 * wrapper is invisible, so the JSON is byte-identical to the bare value (no storage migration).
 * {@link #unwrap()} gives internal consumers typed read access.
 *
 * <p>Because the wire form is the bare value, deserialization needs the element type, which the
 * {@link OpaqueDeserializer} recovers from the field's declared generic type (works for
 * {@code Opaque<T>} and {@code List<Opaque<T>>} alike) — so {@code Opaque} round-trips even on
 * persisted types read back via {@code ObjectMapper.readValue}.
 *
 * <p>Use it on any field whose typed schema is not worth its bytes for the LLM (the model still
 * sees the values at runtime); keep the field {@code nullable = true, requiredMode = NOT_REQUIRED}
 * and a short {@code @Schema(description = ...)} pointer. {@link #of} returns {@code null} for a
 * {@code null} input so the NON_NULL mapper omits it.
 *
 * <p><b>Jackson-version note:</b> {@link JsonValue} is honored by both Jackson 2 and Jackson 3, but
 * {@link OpaqueDeserializer} and its {@link JsonDeserialize} binding are Jackson 2
 * ({@code com.fasterxml.jackson}). Only the Jackson 2 application mapper (snapshot store, usage
 * catalog) ever deserializes {@code Opaque}; the Jackson 3 MCP server mapper only serializes it.
 * When the app moves to Spring Boot 4 / Jackson 3, port the deserializer to {@code tools.jackson}
 * (a {@code ValueDeserializer} + the {@code tools.jackson} {@code @JsonDeserialize}). The
 * {@code OpaqueTest} round-trip runs against the real store mapper and will fail loudly if that
 * mapper migrates before this class is ported.
 */
@JsonDeserialize(using = Opaque.OpaqueDeserializer.class)
public final class Opaque<T> {

    private final Object value;

    private Opaque(Object value) {
        this.value = value;
    }

    /** Wraps {@code value}, or returns {@code null} when {@code value} is {@code null}. */
    public static <T> Opaque<T> of(T value) {
        return value == null ? null : new Opaque<>(value);
    }

    /** Serialization hook: emits the wrapped value unwrapped. Kept {@code Object}-typed so the
     *  schema generator sees no structure to expand. */
    @JsonValue
    Object json() {
        return value;
    }

    /** Typed read access for internal consumers. */
    @SuppressWarnings("unchecked")
    public T unwrap() {
        return (T) value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Opaque<?> other && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return "Opaque[" + value + "]";
    }

    /** Reconstructs {@code Opaque<T>} by resolving {@code T} from the field's declared generic type. */
    static final class OpaqueDeserializer extends JsonDeserializer<Opaque<?>> implements ContextualDeserializer {

        private final JavaType inner;

        OpaqueDeserializer() {
            this.inner = null;
        }

        private OpaqueDeserializer(JavaType inner) {
            this.inner = inner;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
            JavaType declared = property != null ? property.getType() : ctxt.getContextualType();
            return new OpaqueDeserializer(locateInner(declared));
        }

        /** Finds the {@code T} in {@code Opaque<T>}, descending through any container wrapping. */
        private static JavaType locateInner(JavaType type) {
            if (type == null) {
                return null;
            }
            if (type.hasRawClass(Opaque.class)) {
                return type.containedType(0);
            }
            if (type.isContainerType()) {
                return locateInner(type.getContentType());
            }
            return null;
        }

        @Override
        public Opaque<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            return Opaque.of(ctxt.readValue(p, inner));
        }
    }
}
