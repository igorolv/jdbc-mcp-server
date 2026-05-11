# Oracle ADF ViewObject → QueryUsageRecord converter

## What is Oracle ADF ViewObject?

Oracle ADF (Application Development Framework) ViewObject (VO) is a data access
component that encapsulates a SQL query and its result set mapping. Each VO is
defined in an XML file (typically `*View.xml` or `*ViewObj.xml`) and stored
under a `src/` directory in an ADF project.

### Two kinds of ViewObject

| Type | XML attribute | SQL origin |
|---|---|---|
| **Custom query** | `CustomQuery="true"` | `<SQLQuery><![CDATA[SELECT ...]]>` |
| **Entity-based** | `CustomQuery="false"` (default) | Reconstructed from `SelectList` + `FromList` + `Where` attributes |

### Typical project layout

```
adf-demo/
├── Model/
│   └── src/
│       └── demo/model/
│           ├── entities/           # Entity Object XML files
│           │   └── DeptEntity.xml
│           ├── views/              # View Object XML files
│           │   ├── DeptView.xml
│           │   └── EmpView.xml
│           └── common/
│               └── Bundle.properties   # ResourceBundle with business labels
├── ViewController/
│   └── src/
│       └── demo/view/
│           ├── backing/            # Backing beans
│           └── pageDefs/           # Page definitions
└── MyADFApp.jws
```

Any `Model/`, `ViewController/`, etc. wrapper is optional — the converter works
with a flat `src/` as well.

### Resource bundles

ViewObjects reference `.properties` bundle files through `<ResourceBundle>` /
`<PropertiesBundle>` elements. These contain key-value pairs with business
labels for attribute names:

```properties
demo.model.views.DeptView.Deptno_LABEL=Department Number
demo.model.views.DeptView.Dname_LABEL=Department Name
demo.model.views.DeptView.Loc_LABEL=Location
```

The converter resolves these labels for both output columns and the
VO-level business label.

## What this converter does

`convert.py` scans an Oracle ADF project for `ViewObject` XML definitions (in
any `src/` directory) and emits one **QueryUsageRecord** per VO. The output
conforms to `query-usage-record.schema.json` and can be ingested by
jdbc-mcp-server's usage catalog.

### Mapping

| QueryUsageRecord field | Source |
|---|---|
| `source.kind` | Always `"oracle-adf-view"` |
| `source.path` | Relative path from project root to the `.xml` file |
| `source.unit` | `Name` attribute of `<ViewObject>` |
| `businessLabel` | VO-level `LABEL` ResId from ResourceBundle, or VO Name as fallback |
| `businessDomain` | Module name: the directory above `src/` (e.g. `Model`, `ViewController`) or first package segment |
| `businessTags` | `oracle-adf`, module name, `custom-query` / `entity-based` |
| `sql` | `<SQLQuery>` CDATA (custom) or `SELECT SelectList FROM FromList WHERE ...` (entity-based) |
| `parameters` | `<Variable>` elements with `Kind="where"` / `"viewcriteria"` + bind vars detected from SQL text |
| `outputs` | `<ViewAttribute>` elements with `AliasName`, `Expression`, and resolved `businessLabel` from bundle |
| `fieldUsages` | Identity transformation for each output, with resolved `businessObject` label |
| `sourceMeta` | VO name, module, `isCustomQuery` flag |

## Usage

```bash
# No external Python dependencies (stdlib only)

# Convert an entire ADF project
python convert.py /path/to/adf-project --output records.json

# Convert a single module
python convert.py /path/to/adf-project/Model --output model_records.json

# Verbose output
python convert.py /path/to/adf-project --output records.json --verbose
```

### Output example

```json
[
  {
    "schemaVersion": 1,
    "source": {
      "kind": "oracle-adf-view",
      "path": "Model/src/demo/model/views/DeptView.xml",
      "unit": "DeptView"
    },
    "businessLabel": "Departments",
    "businessDomain": "Model",
    "businessTags": ["Model", "entity-based", "oracle-adf"],
    "sql": "SELECT Dept.DEPTNO, Dept.DNAME, Dept.LOC FROM DEPT Dept",
    "parameters": [
      {"name": "FilterDeptno", "dataType": "numeric"}
    ],
    "outputs": [
      {"alias": "DEPTNO", "sourceExpression": "Dept.DEPTNO", "businessLabel": "Department Number"},
      {"alias": "DNAME", "sourceExpression": "Dept.DNAME", "businessLabel": "Department Name"},
      {"alias": "LOC", "sourceExpression": "Dept.LOC", "businessLabel": "Location"}
    ],
    "fieldUsages": [
      {
        "output": "DEPTNO",
        "businessObject": "Department Number",
        "transformation": {"kind": "identity"},
        "location": {"kind": "view-attribute"},
        "confidence": "high"
      }
    ],
    "sourceMeta": {
      "adapter": "oracle-adf",
      "adapterVersion": "1.0.0",
      "viewObjectName": "DeptView",
      "module": "Model",
      "isCustomQuery": false
    }
  }
]
```

## Details

### How ViewObject files are found

The converter scans all `src/` directories that exist directly under any
subdirectory of the project root (e.g. `Model/src/`, `ViewController/src/`)
or a top-level `src/`. Directories containing `classes/` or `lib/` in their
path are skipped to avoid compiled copies.

```
adf-demo/
├── Model/src/       ← scanned
├── classes/         ← skipped
└── lib/             ← skipped
```

### Module detection

The module name is taken from the directory immediately before `src/`:
- `Model/src/demo/model/views/DeptView.xml` → `Model`
- `ViewController/src/demo/view/...` → `ViewController`
- `src/demo/model/views/DeptView.xml` → `demo` (first package segment)

### Parameter detection

- Bind variables declared in `<Variable Kind="where|viewcriteria">` are captured
  with their declared Java type (`oracle.jbo.domain.Number` → `numeric`, etc.)
- Additional bind parameters are detected from the SQL text via regex
  (`:paramName` outside string literals)
- Only named bind parameters (`:ParamName`) are supported (Oracle-style)

### Resource bundle resolution

The converter pre-loads ALL `.properties` files under `src/` directories
into a bundle cache. For each ViewObject it resolves:

1. **VO-level label** — from `<LABEL ResId="...">` in `<ViewObject><Properties><SchemaBasedProperties>`
2. **Attribute-level labels** — from `<LABEL ResId="...">` in `<ViewAttribute><Properties><SchemaBasedProperties>`
3. **Bundle key format** — `<dotted_package>.<AttributeName>_LABEL`

### Handling entity-based VOs

ViewObjects without `CustomQuery="true"` generate SQL dynamically from entity
attribute definitions. The converter reconstructs:

```sql
SELECT <SelectList> FROM <FromList> [WHERE <Where>] [ORDER BY <OrderBy>]
```

This provides enough context for the usage catalog even when raw SQL is not
stored in the XML.

## Validation

```bash
pip install check-jsonschema
# Validate individual records (the schema expects one record at a time)
python -c "
import json, jsonschema
with open('records.json') as f: data = json.load(f)
with open('../../../src/main/resources/schemas/query-usage-record.schema.json') as f: schema = json.load(f)
for i, r in enumerate(data):
    try: jsonschema.validate(r, schema)
    except jsonschema.ValidationError as e: print(f'Record {i}: {e.message}')
"
```

## See also

- [query-usage-record.schema.json](../../../src/main/resources/schemas/query-usage-record.schema.json)
- [bi-publisher converter](../bi-publisher/README.md) — similar converter for Oracle BI Publisher reports