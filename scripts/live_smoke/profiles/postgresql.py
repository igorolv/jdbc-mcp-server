from __future__ import annotations

from .base import DbProfile, sql_string


def _default_schema(env: dict[str, str]) -> str | None:
    return env.get("LIVE_POSTGRES_SCHEMA") or "public"


def _first_view_sql(schema: str | None) -> str:
    schema_expr = sql_string(schema) if schema else "current_schema()"
    return (
        "SELECT table_schema, table_name FROM information_schema.views "
        f"WHERE table_schema = {schema_expr} "
        "ORDER BY table_name LIMIT 1"
    )


def _quote_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


POSTGRESQL = DbProfile(
    name="postgresql",
    env_prefix="LIVE_POSTGRES",
    ping_sql="SELECT 1 AS v",
    default_schema=_default_schema,
    first_view_sql=_first_view_sql,
    view_schema_column="table_schema",
    view_name_column="table_name",
    quote_ident=_quote_ident,
)
