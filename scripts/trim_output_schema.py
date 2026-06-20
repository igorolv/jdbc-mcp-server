#!/usr/bin/env python3
"""One-shot refactor: strip name-redundant field-level @Schema descriptions from MCP output models.

Only removes the `description = "..."` text of FIELD-level @Schema annotations whose prose merely
restates the field name. Keeps: record/type-level descriptions, enumerations ("such as X, Y, Z"),
order-significance and null-semantics caveats, and every unique/specific description. Leaves
`nullable` / `requiredMode` untouched (they are load-bearing: properties are required by default).
"""
import pathlib
import sys

MODEL_DIR = pathlib.Path("src/main/java/ru/it_spectrum/ai/jdbc/mcp/model")

# Exact field-description texts (without the `description = "..."` wrapper) to drop.
# These purely restate an obvious field name and add nothing the name + type don't convey.
REMOVE = [
    "Database schema or owner that qualifies the object.",
    "Object name as reported by database metadata or parsed SQL.",
    "Table name within the schema.",
    "Column name within the table.",
    "Table name resolved by parser and metadata matching.",
    "Schema resolved by parser and metadata matching.",
    "Schema of the source or left-side table in the relationship.",
    "Source or left-side table in the relationship.",
    "Target or right-side table in the relationship.",
    "Schema of the target or right-side table in the relationship.",
    "Table referenced by a foreign key or constraint.",
    "Schema of the table referenced by a foreign key or constraint.",
    "Table name for a finding or statistics row.",
    "Database object type, SQL construct type, or engine-specific classification.",
    "Human-readable business label attached to the query, parameter, or output.",
    # `remarks` describes the DB COMMENT and is kept (JDBC jargon, not obvious from the field name).
    "Number of rows returned or represented in this response.",
    "Human-readable diagnostic message.",
    "Human-readable error message explaining why the requested operation failed.",
]


def main() -> int:
    total = 0
    for path in MODEL_DIR.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        original = text
        n = 0
        for desc in REMOVE:
            needle = f'description = "{desc}", '
            cnt = text.count(needle)
            if cnt:
                text = text.replace(needle, "")
                n += cnt
        if text != original:
            path.write_text(text, encoding="utf-8")
            total += n
            print(f"{n:3d}  {path}")
    print(f"\nremoved {total} field descriptions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
