package ru.it_spectrum.ai.jdbc.mcp.model;

import com.fasterxml.jackson.annotation.JsonValue;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

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
 * <p><b>Jackson-version note:</b> this class is fully on Jackson 3 ({@code tools.jackson}). The
 * {@link JsonValue} serialization hook stays in {@code com.fasterxml.jackson.annotation} (the
 * annotations package Jackson 3 kept), while {@link OpaqueDeserializer} is a
 * {@link ValueDeserializer} bound via the {@code tools.jackson} {@link JsonDeserialize}. The single
 * application {@code JsonMapper} now both serializes and deserializes {@code Opaque}; the
 * {@code OpaqueTest} round-trip exercises that mapper end to end.
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
    static final class OpaqueDeserializer extends ValueDeserializer<Opaque<?>> {

        private final JavaType inner;

        OpaqueDeserializer() {
            this.inner = null;
        }

        private OpaqueDeserializer(JavaType inner) {
            this.inner = inner;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
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
        public Opaque<?> deserialize(JsonParser p, DeserializationContext ctxt) {
            return Opaque.of(ctxt.readValue(p, inner));
        }
    }
}
