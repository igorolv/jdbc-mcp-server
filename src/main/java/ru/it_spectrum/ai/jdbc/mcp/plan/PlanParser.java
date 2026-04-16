package ru.it_spectrum.ai.jdbc.mcp.plan;

import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

/**
 * Turns the raw rows returned by a structured EXPLAIN (PostgreSQL JSON or Oracle PLAN_TABLE)
 * into a unified {@link ParsedPlan}. One implementation per database engine; resolved
 * through {@link ru.it_spectrum.ai.jdbc.mcp.dialect.DialectConfig}.
 */
public interface PlanParser {

    /**
     * Parse the result of the engine's structured-plan statement.
     *
     * @param result   raw rows — PG: a single-row {@code "QUERY PLAN"} column holding the JSON;
     *                 Oracle: all {@code PLAN_TABLE} rows for the current statement id.
     * @param analyzed {@code true} if the plan is the result of an ANALYZE run (actual rows/times attached).
     *                 Oracle always produces a static plan, so this flag is false there.
     */
    ParsedPlan parse(QueryResult result, boolean analyzed);
}
