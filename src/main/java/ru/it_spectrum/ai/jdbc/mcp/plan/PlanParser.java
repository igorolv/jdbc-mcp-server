package ru.it_spectrum.ai.jdbc.mcp.plan;

import ru.it_spectrum.ai.jdbc.mcp.sql.QueryResult;

/**
 * Turns the raw rows returned by a structured EXPLAIN / SHOWPLAN (PostgreSQL JSON,
 * Oracle PLAN_TABLE, or SQL Server SHOWPLAN_XML) into a unified {@link ParsedPlan}.
 * One implementation per database engine; resolved
 * through {@link ru.it_spectrum.ai.jdbc.mcp.dialect.DialectConfig}.
 */
public interface PlanParser {

    /**
     * Parse the result of the engine's structured-plan statement.
     *
     * @param result   raw rows — PG: a single-row {@code "QUERY PLAN"} column holding the JSON;
     *                 Oracle: all {@code PLAN_TABLE} rows for the current statement id;
     *                 SQL Server: one or more XML showplan rows.
     * @param analyzed {@code true} if the plan is the result of an ANALYZE run (actual rows/times attached).
     *                 Oracle and SQL Server always produce static / estimated plans here.
     */
    ParsedPlan parse(QueryResult result, boolean analyzed);
}
