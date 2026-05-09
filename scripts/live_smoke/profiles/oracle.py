from __future__ import annotations

from .base import DbProfile, sql_string


def _default_schema(env: dict[str, str]) -> str | None:
    explicit = env.get("LIVE_ORACLE_SCHEMA")
    if explicit:
        return explicit.upper()
    username = env.get("LIVE_ORACLE_USERNAME")
    return username.upper() if username else None


def _first_view_sql(schema: str | None) -> str:
    owner_expr = sql_string(schema.upper()) if schema else "USER"
    return (
        "SELECT owner, view_name FROM all_views "
        f"WHERE owner = {owner_expr} "
        "ORDER BY view_name FETCH FIRST 1 ROWS ONLY"
    )


def _quote_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


ORACLE = DbProfile(
    name="oracle",
    env_prefix="LIVE_ORACLE",
    ping_sql="SELECT 1 AS v FROM dual",
    default_schema=_default_schema,
    first_view_sql=_first_view_sql,
    view_schema_column="OWNER",
    view_name_column="VIEW_NAME",
    quote_ident=_quote_ident,
)
