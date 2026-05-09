from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class DbProfile:
    name: str
    env_prefix: str
    ping_sql: str
    default_schema: Callable[[dict[str, str]], str | None]
    first_view_sql: Callable[[str | None], str]
    view_schema_column: str
    view_name_column: str
    quote_ident: Callable[[str], str]

    def qualified_name(self, schema: str | None, name: str) -> str:
        if schema:
            return f"{self.quote_ident(schema)}.{self.quote_ident(name)}"
        return self.quote_ident(name)


def sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"
