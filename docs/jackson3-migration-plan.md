# Jackson 2 → Jackson 3 consolidation plan

> Status: **done.** Our code is fully on Jackson 3 (`tools.jackson`) and the explicit
> `jackson-databind` dependency is gone. `compileJava`, `compileTestJava`, and the unit suite
> all pass.
>
> **Key deviation from the original plan:** the "Jackson 2 stays transitively via victools"
> assumption was **false** for this build — Spring AI 2.0 here generates schemas with a Jackson 3
> path, so dropping the explicit `jackson-databind` removed Jackson 2 from the classpath
> entirely. Step 5's "any compile error reveals a leftover J2 import" therefore surfaced **22 test
> files** (mostly integration-test JSON-node helpers), not a handful. All were migrated to
> `tools.jackson`. Jackson 3 kept the `JsonNode` accessor names (`asText()`, `textValue()`,
> `asInt()`, …) as delegating methods, so those call sites were untouched; the only real API
> changes were `JsonNode.fields()` → `properties()` (returns a `Set`), `JsonProcessingException`
> → unchecked `tools.jackson.core.JacksonException`, and `JsonMappingException` →
> `tools.jackson.databind.DatabindException`. `jackson-annotations` stays
> (`com.fasterxml.jackson.annotation.*` is unchanged), so `@JsonValue`/`@JsonInclude`/enum
> annotations needed no edits. `new ObjectMapper()` is still valid in J3 (public no-arg ctor).

## Baseline (already true)

- **Spring Boot 4.0.0** and **Spring AI 2.0.0** are already in use (`gradle/libs.versions.toml`).
  This is **not** a Boot upgrade — Boot 4 is done.
- Two Jacksons coexist on the runtime classpath:
  - **Jackson 3** (`tools.jackson:jackson-databind:3.0.2`) — the Boot 4 / MCP default, via
    `io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.0`. Serializes MCP tool responses and
    validates them against the generated output schema.
  - **Jackson 2** (`com.fasterxml.jackson:2.20.1`) — pulled by (a) our **explicit**
    `jackson-databind` dependency, and (b) **`com.github.victools:jsonschema-module-jackson:5.0.0`**,
    the Jackson-2 schema generator behind Spring AI's `JsonSchemaGenerator`.
- Our own JSON code is still on Jackson 2: `config/JsonConfig`, `tools/JsonResponses`,
  `plan/PostgresPlanParser`, `dialect/DialectConfig`, `metadata/SqliteStructureSnapshotStore`,
  `usage/UsageCatalogService`, and `model/Opaque` (serialize + the contextual deserializer).

## Goal & non-goals

- **Goal:** move our own JSON code to Jackson 3 (`tools.jackson`), drop our **explicit**
  `jackson-databind` dependency, and remove `Opaque`'s "deserializer is Jackson-2-only" caveat so
  one mapper both serializes and deserializes it.
- **Non-goal:** fully removing Jackson 2 from the build. `victools jsonschema-module` (schema
  generation) keeps Jackson 2 transitively; that is isolated to schema generation and does not
  touch our code. Getting our code onto a single (J3) mapper is the win, not a zero-J2 classpath.

## Verified Jackson 3 facts (from the official migration guide)

- **Annotations split:**
  - *Stay* in `com.fasterxml.jackson.annotation` (jackson-annotations keeps its 2.x package):
    `@JsonProperty`, `@JsonValue`, `@JsonCreator`, `@JsonInclude`, `@JsonPropertyDescription`, …
    → our `usage/format/*` files and `Opaque`'s `@JsonValue` need **no change**.
  - *Move* to `tools.jackson.databind.annotation`: `@JsonSerialize`, `@JsonDeserialize`.
    → `Opaque`'s `@JsonDeserialize` import must change.
- **Deserializer API:** `JsonDeserializer` → `ValueDeserializer`; `ContextualDeserializer` is
  **removed** and `createContextual(DeserializationContext, BeanProperty)` is now a method on
  `ValueDeserializer` (same signature). (`ResolvableDeserializer.resolve()` likewise folded in.)
- **Exceptions are unchecked:** `JsonProcessingException` → `JacksonException extends RuntimeException`
  (not `IOException`). `throws IOException` / `catch JsonProcessingException` can be dropped.
- **Default changes that can bite us:**
  - `FAIL_ON_UNKNOWN_PROPERTIES` now **disabled** by default (was on in 2.x). *Helps* our snapshot
    round-trip — stale/extra fields are ignored.
  - `SORT_PROPERTIES_ALPHABETICALLY` now **enabled** by default. ⚠️ Reorders JSON keys — can break
    any test asserting an exact JSON string and changes MCP response key order. Decide explicitly
    (disable it, or accept). Our current J3 server mapper does **not** sort (OpaqueTest asserts
    `{"payload":..,"kept":..}` and passes), so to preserve behavior, **disable sorting** in our mapper.
  - `WRITE_DATES_AS_TIMESTAMPS` moved to `DateTimeFeature` and is disabled.
- **Spring Boot 4 escape hatch:** `spring.jackson.use-jackson2-defaults=true` restores 2.x defaults
  (unknown-properties, sorting, …) globally — a low-risk first switch if behavior drift appears.
- **Boot 4 customization:** Boot autoconfigures a Jackson 3 `JsonMapper`; customize via a
  `JsonMapperBuilderCustomizer` bean (the J3 analogue of `Jackson2ObjectMapperBuilderCustomizer`),
  or build our own `JsonMapper` in `JsonConfig` (current approach).

## Inventory

| File | Uses | Action |
|---|---|---|
| `config/JsonConfig` | `ObjectMapper`/`JsonMapper`, `SerializationFeature`, `JsonInclude` | Port to `tools.jackson` JsonMapper |
| `tools/JsonResponses` | `ObjectMapper`, `JsonProcessingException` | Port; drop checked exception |
| `plan/PostgresPlanParser` | `ObjectMapper`, `readValue` | Port |
| `dialect/DialectConfig` | `ObjectMapper` | Port |
| `metadata/SqliteStructureSnapshotStore` | `ObjectMapper`, `JsonProcessingException`, `readValue`/`writeValueAsString` | Port; drop checked exception |
| `usage/UsageCatalogService` | `JsonNode`, `ObjectMapper` | Port |
| `model/Opaque` | databind deserializer + `@JsonDeserialize` | Port deserializer to J3 (see below); keep `@JsonValue` |
| `usage/format/*` (QueryUsage*) | annotations only | **No change** (annotations stay in `com.fasterxml.jackson.annotation`) |

## Steps

1. **`JsonConfig` → Jackson 3.** Rebuild `jdbcMcpObjectMapper` as a `tools.jackson.databind.json.JsonMapper`
   with the same explicit settings (`JsonInclude.NON_NULL`, `FAIL_ON_EMPTY_BEANS` off,
   `WRITE_DATES_AS_TIMESTAMPS`/`DateTimeFeature` off) **and explicitly disable
   `SORT_PROPERTIES_ALPHABETICALLY`** to preserve current key order. One bean flips every injector.
2. **Port the 6 databind consumers** — swap imports to `tools.jackson`; `readValue` /
   `writeValueAsString` / `readTree` / `JsonNode` are ~1:1. Remove `throws/catch` of
   `JsonProcessingException` (now unchecked).
3. **Port `Opaque` to Jackson 3** (the only non-trivial port):
   ```java
   import com.fasterxml.jackson.annotation.JsonValue;            // stays (jackson-annotations)
   import tools.jackson.core.JsonParser;
   import tools.jackson.databind.BeanProperty;
   import tools.jackson.databind.DeserializationContext;
   import tools.jackson.databind.JavaType;
   import tools.jackson.databind.ValueDeserializer;              // was JsonDeserializer
   import tools.jackson.databind.annotation.JsonDeserialize;     // moved package

   @JsonDeserialize(using = Opaque.OpaqueDeserializer.class)
   public final class Opaque<T> {
       private final Object value;
       @JsonValue Object json() { return value; }                // unchanged
       public T unwrap() { ... }                                 // unchanged
       // equals/hashCode/of unchanged

       static final class OpaqueDeserializer extends ValueDeserializer<Opaque<?>> {
           private final JavaType inner;
           // createContextual is now an override on ValueDeserializer (no ContextualDeserializer)
           @Override public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
               JavaType t = property != null ? property.getType() : ctxt.getContextualType();
               return new OpaqueDeserializer(locateInner(t));
           }
           @Override public Opaque<?> deserialize(JsonParser p, DeserializationContext ctxt) {
               return Opaque.of(ctxt.readValue(p, inner));        // no checked IOException in J3
           }
       }
   }
   ```
   After this the same J3 mapper serializes **and** deserializes `Opaque` — the dual-Jackson caveat is gone.
4. **Schema generation untouched.** `JsonSchemaGenerator` (victools, J2) is independent of our mapper;
   schema sizes and `Opaque`'s opaque rendering do **not** change. (This cleanly separates "mapper
   migration" from "schema output".)
5. **Drop the explicit `jackson-databind`** from `build.gradle.kts`. Rebuild; any compile error reveals
   a leftover J2 import to fix. (J2 stays transitively via victools — expected.)
6. **`OpaqueTest`.** Its round-trip already uses `new JsonConfig().jdbcMcpObjectMapper()`; after step 1
   that is a J3 mapper, so the test now exercises a real J3 round-trip — the former "tripwire" becomes
   the actual coverage.

## Verification

- Full unit suite — especially `SqliteStructureSnapshotStoreTest` (`TableDescription` + `Opaque`
  round-trip), `OpaqueTest`, `OutputSchemaSmokeTest`.
- `OutputSchemaSizeTest` — totals must be **unchanged** (generator is unchanged).
- Integration (at least Postgres): `describeTable` / `tableContext` / `queryContext` — response
  serialization + schema validation.
- Eyeball J3 default drift on snapshot JSON and MCP responses (key order, dates). If drift appears,
  the quickest mitigation is `spring.jackson.use-jackson2-defaults=true` while pinning the few
  features we rely on.

## Sources

- [Migrating to Jackson 3 (official)](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)
- [Jackson Release 3.0 wiki](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.0)
- [ValueDeserializer javadoc (databind 3.0)](https://javadoc.io/static/tools.jackson.core/jackson-databind/3.0.0/tools.jackson.databind/tools/jackson/databind/ValueDeserializer.html)
- [Jackson 3 in Spring Boot 4 (Dan Vega)](https://www.danvega.dev/blog/jackson-3-spring-boot-4)
- [Upgrading to Jackson 3 with Spring Boot 4 (Dimitri)](https://dimitri.codes/jsonmapper/)
- [Spring Boot issue #49951 — use-jackson2-defaults](https://github.com/spring-projects/spring-boot/issues/49951)
