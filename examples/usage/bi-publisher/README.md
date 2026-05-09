# BI Publisher → QueryUsageRecord converter

## What is Oracle BI Publisher?

Oracle BI Publisher (also known as Oracle XML Publisher) is a report-building tool that
produces formatted documents (PDF, RTF, XLS, HTML) from SQL queries. A BI Publisher report
is stored as a pair of directories on disk:

| Directory | Contents |
|---|---|
| `*.xdo/` | `_report.xdo` — report metadata (description, template references) + template files (.rtf, .xls) |
| `*.xdm/` | `_datamodel.xdm` — data model: parameters, SQL datasets, XPath data structure |

### Source layout example

```
RTK/
  Notif/
    Stated.xdo/
      _report.xdo
      Stated_ru.rtf
      Stated_en.rtf
    Stated.xdm/
      _datamodel.xdm
    ACT.xdo/
      _report.xdo
      Agr_ru.rtf
      Act_ru.rtf
    ACT.xdm/
      _datamodel.xdm
```

## What this converter does

`convert.py` scans a directory tree for `.xdo`/`.xdm` pairs, parses the XML, RTF, and XLS
files, and emits one **QueryUsageRecord** per SQL dataset. The output conforms to
`query-usage-record.schema.json` and can be ingested by jdbc-mcp-server's usage catalog.

### Mapping

| QueryUsageRecord field | Source |
|---|---|
| `dataSource` | `_datamodel.xdm` → `defaultDataSourceRef` attribute |
| `source.kind` | Always `"bi-publisher-report"` |
| `source.path` | Relative path to the `.xdo` directory |
| `source.unit` | Dataset name from `_datamodel.xdm` |
| `businessLabel` | Report description + ` / ` + dataset name |
| `businessDomain` | Top-level module directory (e.g. `GPN`, `RTK`, `SSV`) |
| `sql` | SQL text from `<dataSet><sql>` in `_datamodel.xdm` |
| `parameters` | Report-level parameters + bind parameters detected from SQL |
| `outputs` | Data structure elements (`<group><element name="..." value="...">`) |
| `fieldUsages` | Field references extracted from RTF/Excel templates |
| `sourceMeta` | Report and template metadata for audit trail |

### Field usage extraction

- **Excel (.xls)** — Reads `XDO_?field?` named ranges via `xlrd`, resolves the
  sheet name and cell address from the formula, walks up nearby cells for
  column header context.
- **RTF (.rtf)** — Scans for `<?field?>` BI Publisher expressions and
  `\*\docvar` declarations via regex.

## Usage

```bash
# Install dependency (xlrd for .xls template parsing)
pip install -r requirements.txt

# Convert all reports under a BI Publisher source tree
python convert.py /path/to/bi-publisher/sources --output records.json

# Convert a single module
python convert.py /path/to/RTK --output rtk_records.json

# Convert a single report
python convert.py /path/to/RTK/Notif/Stated.xdo --output stated.json
```

### Output

A JSON array of QueryUsageRecord objects:

```json
[
  {
    "schemaVersion": 1,
    "dataSource": "SSKV",
    "source": {
      "kind": "bi-publisher-report",
      "path": "RTK/Notif/Stated.xdo",
      "unit": "MAIN"
    },
    "businessLabel": "Уведомление (общее) / MAIN",
    "businessDomain": "RTK",
    "businessTags": ["RTK", "SSKV", "bi-publisher", "complex"],
    "sql": "SELECT ... FROM ... WHERE ...",
    "parameters": [
      {"name": "pBankruptId", "dataType": "xsd:string"},
      {"name": "pStatus", "dataType": "xsd:string", "defaultValue": "ACTIVE"}
    ],
    "outputs": [
      {"alias": "CREDITOR_ID", "sourceExpression": "CREDITOR_ID", "businessLabel": "CREDITOR_ID"},
      {"alias": "NP_SURNAME", "sourceExpression": "NP_SURNAME"}
    ],
    "fieldUsages": [
      {
        "businessObject": "Уведомление (общее)",
        "transformation": {"kind": "identity"},
        "location": {
          "kind": "excel-cell",
          "details": {"sheet": "Main", "cell": "B3"}
        },
        "headers": ["Фамилия"],
        "confidence": "high"
      }
    ],
    "sourceMeta": {
      "adapter": "bi-publisher",
      "adapterVersion": "1.0.0",
      "report": "Stated",
      "dataset": "MAIN",
      "module": "RTK",
      "templates": [
        {"label": "Уведомление", "type": "rtf", "url": "Stated.rtf"}
      ]
    }
  }
]
```

## Validation

To validate output against the schema:

```bash
pip install check-jsonschema
check-jsonschema --schemafile ../../../src/main/resources/schemas/query-usage-record.schema.json records.json
```

## See also

- [bi-publisher project](https://github.com/anomalyco/bi-publisher) — raw BI Publisher
  source tree and the full parsing pipeline this converter is based on.
- [query-usage-record.schema.json](../../../src/main/resources/schemas/query-usage-record.schema.json)
  — canonical schema for jdbc-mcp-server usage catalog ingestion.