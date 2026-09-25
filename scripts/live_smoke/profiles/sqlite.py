from __future__ import annotations

from .base import DbProfile


def _default_schema(env: dict[str, str]) -> str | None:
    return env.get("LIVE_SQLITE_SCHEMA") or "main"


def _first_view_sql(schema: str | None) -> str:
    return (
        "SELECT 'main' AS view_schema, name AS view_name FROM sqlite_schema "
        "WHERE type = 'view' ORDER BY name LIMIT 1"
    )


def _quote_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


SQLITE = DbProfile(
    name="sqlite",
    env_prefix="LIVE_SQLITE",
    ping_sql="SELECT 1 AS v",
    default_schema=_default_schema,
    first_view_sql=_first_view_sql,
    view_schema_column="view_schema",
    view_name_column="view_name",
    quote_ident=_quote_ident,
    credentials=False,
)
