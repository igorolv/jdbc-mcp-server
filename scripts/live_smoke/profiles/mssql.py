from __future__ import annotations

from .base import DbProfile, sql_string


def _default_schema(env: dict[str, str]) -> str | None:
    return env.get("LIVE_MSSQL_SCHEMA") or "dbo"


def _first_view_sql(schema: str | None) -> str:
    schema_expr = sql_string(schema) if schema else "SCHEMA_NAME()"
    return (
        "SELECT TOP (1) TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.VIEWS "
        f"WHERE TABLE_SCHEMA = {schema_expr} "
        "ORDER BY TABLE_NAME"
    )


def _quote_ident(value: str) -> str:
    return "[" + value.replace("]", "]]") + "]"


MSSQL = DbProfile(
    name="mssql",
    env_prefix="LIVE_MSSQL",
    ping_sql="SELECT 1 AS v",
    default_schema=_default_schema,
    first_view_sql=_first_view_sql,
    view_schema_column="TABLE_SCHEMA",
    view_name_column="TABLE_NAME",
    quote_ident=_quote_ident,
)
