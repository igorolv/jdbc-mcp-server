# USER_QUERY → QueryUsageRecord converter

## What is USER_QUERY?

Query definitions and their parameters can live in the database itself — stored in
special-purpose tables rather than in application source files. This adapter
illustrates that pattern: it reads SQL text and parameter metadata directly from
an Oracle table and converts them to QueryUsageRecord format.

`USER_QUERY` is a table storing user-defined parameterized SQL queries
for a report/reference system. Each row represents one named query with:

| Column | Type | Description |
|---|---|---|
| `USER_QUERY_ID` | NUMBER(6) | Primary key |
| `SUBSYSTEM_CODE` | VARCHAR2(1) | Subsystem code (FK to SUBSYSTEM) |
| `UQ_NAME` | VARCHAR2(100) | Query name |
| `UQ_DEF` | CLOB | XML with SQL text and parameter definitions |
| `UQ_LOV_FLAG` | NUMBER(1) | LOV (list-of-values) flag |
| `UQ_TYPE_ENUM` | NUMBER(1) | 1=SQL record, 2=ref cursor |
| `UQ_ARCHIVE_FLAG` | NUMBER(1) | Archived report flag |
| `MASTER_USER_QUERY_ID` | NUMBER(6) | Parent query ID (self-referencing FK) |

### UQ_DEF XML structure

```xml
<?xml version="1.0" encoding="WINDOWS-1251"?>
<uqDef>
    <text>select * from users u where u.user_id = :s_user</text>
    <var NAME="s_user" LABEL="User" TYPE="NUMBER"
         KIND="HR_VAR" REQUIRED="true"
         HR_VAR="CURR_USER_ID"/>
    <rowCountDisable>false</rowCountDisable>
</uqDef>
```

Parameter `<var>` attributes:
- `NAME` — bind variable name
- `LABEL` — human-readable label
- `TYPE` — data type (NUMBER, VARCHAR2, DATETIME, etc.)
- `KIND` — QU_LOV, HR_VAR, CONST
- `LOV_ID` — references another USER_QUERY_ID for LOV values
- `SSKV_VAR` — system variable mapping

## What this converter does

`convert.py` connects to the Oracle database, reads all rows from
`USER_QUERY` (joined with `SUBSYSTEM` for human-readable domain names),
parses the XML in `UQ_DEF` to extract SQL text and parameters, and emits one
**QueryUsageRecord** per row.

The schema is resolved via `LIVE_ORACLE_SCHEMA` env var (same logic as
`LiveOracleIntegrationSchemaTest`): if set, it is used directly; otherwise
the username (upper-cased) is used. The `--schema` CLI argument overrides both.

### Mapping

| QueryUsageRecord field | Source |
|---|---|
| `source.kind` | Always `"user-query"` |
| `source.path` | `"<USER_QUERY_ID>"` |
| `source.unit` | `UQ_NAME` |
| `businessLabel` | `UQ_NAME` |
| `businessDomain` | `SUBSYSTEM_NAME` from JOIN with SUBSYSTEM (falls back to SUBSYSTEM_CODE) |
| `businessTags` | `user-query`, subsystem code, `lov`, `sql-record`/`ref-cursor`, `archive`, `child-query` |
| `sql` | `<text>` element from UQ_DEF XML |
| `parameters` | `<var>` elements + bind parameters detected from SQL text |
| `sourceMeta` | USER_QUERY_ID, UQ_TYPE_ENUM, UQ_LOV_FLAG, UQ_ARCHIVE_FLAG, MASTER_USER_QUERY_ID, rowCountDisable, etc. |

## Usage

```bash
# Install dependency
pip install -r requirements.txt

# Set Oracle connection env vars
set LIVE_ORACLE_URL=jdbc:oracle:thin:@//host:1521/service
    set LIVE_ORACLE_USERNAME=scott
set LIVE_ORACLE_PASSWORD=***

# Run
python convert.py --output user-query.json
```

You can also pass credentials via CLI arguments:

```bash
python convert.py \
  --url jdbc:oracle:thin:@//host:1521/service \
  --username scott \
  --password *** \
  --output user-query.json
```

CLI arguments override env vars.

### Output example

```json
[
  {
    "schemaVersion": 1,
    "source": {
      "kind": "user-query",
      "path": "100",
      "unit": "Department Employees"
    },
    "businessLabel": "Department Employees",
    "businessDomain": "Human Resources",
    "businessTags": ["hr", "lov", "sql-record", "user-query"],
    "sql": "SELECT e.empno, e.ename, e.job, e.sal FROM emp e WHERE e.deptno = :dept_id",
    "parameters": [
      {
        "name": "dept_id",
        "dataType": "NUMBER",
        "businessLabel": "Department",
        "required": true
      }
    ],
    "sourceMeta": {
      "adapter": "user-query",
      "adapterVersion": "1.0.0",
      "userQueryId": 100,
      "subsystemCode": "HR",
      "subsystemName": "Human Resources",
      "uqTypeEnum": 1,
      "uqLovFlag": true,
      "uqArchiveFlag": false,
      "rowCountDisable": false
    }
  },
  {
    "schemaVersion": 1,
    "source": {
      "kind": "user-query",
      "path": "200",
      "unit": "Manager Report"
    },
    "businessLabel": "Manager Report",
    "businessDomain": "Management",
    "businessTags": ["mgt", "sql-record", "user-query"],
    "sql": "SELECT d.dname, d.loc, COUNT(e.empno) AS emp_count\nFROM dept d\nLEFT JOIN emp e ON e.deptno = d.deptno\nGROUP BY d.dname, d.loc\nORDER BY d.dname",
    "parameters": [],
    "sourceMeta": {
      "adapter": "user-query",
      "adapterVersion": "1.0.0",
      "userQueryId": 200,
      "subsystemCode": "MGT",
      "subsystemName": "Management",
      "uqTypeEnum": 1,
      "uqLovFlag": false,
      "uqArchiveFlag": false,
      "rowCountDisable": false
    }
  }
]
```

## Validation

```bash
pip install check-jsonschema
python -c "
import json, jsonschema
with open('user-query.json', encoding='utf-8') as f: data = json.load(f)
with open('../../../src/main/resources/schemas/query-usage-record.schema.json') as f: schema = json.load(f)
for i, r in enumerate(data):
    try: jsonschema.validate(r, schema)
    except jsonschema.ValidationError as e: print(f'Record {i}: {e.message}')
"
```

