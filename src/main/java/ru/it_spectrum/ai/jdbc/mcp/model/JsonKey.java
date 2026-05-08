package ru.it_spectrum.ai.jdbc.mcp.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the JSON key name for a record component when serialised by {@code JsonWriter}.
 * Use when the Java field name differs from the desired JSON key — for example snake_case
 * keys or Java reserved words like {@code default}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface JsonKey {
    String value();
}
