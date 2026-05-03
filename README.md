# JDBC MCP Server

Локальный MCP-сервер для read-only доступа к базам данных PostgreSQL и Oracle.
Позволяет AI-агентам (Claude Code, Cursor, VS Code Copilot и др.) писать SQL-запросы,
получать планы выполнения и исследовать структуру базы — таблицы, колонки, индексы,
внешние ключи, представления, функции, последовательности.

Драйверы PostgreSQL и Oracle JDBC встроены в fat jar — ничего дополнительно ставить не нужно.

## Для чего это

Сценарий: вы просите LLM «посмотри в базе, сколько у нас заказов по статусам за последний месяц».
Без этого сервера LLM может:
- выдумывать названия таблиц и колонок;
- не учесть реальную схему (nullable-поля, типы, внешние ключи);
- в пылу «размышления» случайно сгенерировать `DELETE` или `TRUNCATE`.

С сервером LLM:
1. вызывает `schemaOverview` / `schemaBrief` / `queryContext` — получает готовый контекст схемы (таблицы, колонки, связи, ограничения);
2. при необходимости уточняет — `tableContext` вокруг конкретной таблицы или `findJoinPaths` для поиска путей JOIN;
3. пишет запрос и вызывает `validateQuery` с теми же `params` / `namedParams`, которые потом пойдут в execution-tool — проверяет синтаксис без выполнения;
4. при необходимости вызывает `explainQuery` — смотрит план;
5. вызывает `executeQuery` — получает данные.

Любой non-SELECT запрос блокируется ещё до отправки в БД.

## Архитектура

```
┌─────────────┐     stdio      ┌──────────────────┐      JDBC      ┌──────────┐
│  AI-агент   │ <------------> │  jdbc-mcp-       │ -------------> │ PG / OCI │
│ (Claude Code│   stdin/stdout │  server (Java)   │  read-only     │ database │
│  Cursor...) │                │                  │  connection    │          │
└─────────────┘                └──────────────────┘                └──────────┘
```

Протокол — только `stdio`. Клиент запускает сервер как дочерний процесс.

## Инструменты (MCP tools)

### Запросы

| Tool | Описание |
|---|---|
| `executeQuery` | Выполнить `SELECT` / `WITH` / `EXPLAIN`. Параметры: `sql`, `params` (массив для `?`) или `namedParams` (объект для `:name`), `limit`, `timeoutSeconds`, `format` (`json` по умолчанию, `markdown`, `csv`). Результат помечается `truncated: true`, если превысил лимит |
| `explainQuery` | План выполнения запроса. PG: `EXPLAIN (FORMAT TEXT)`. Oracle: `EXPLAIN PLAN FOR` + `DBMS_XPLAN.DISPLAY`. Параметры можно передавать как `params` (`?`) или `namedParams` (`:name`). Флаг `analyze=true` (PG) включает `EXPLAIN ANALYZE` — осторожно, запрос реально выполнится |
| `analyzePlan` | Компактная LLM-ориентированная сводка плана вместо многостраничного дампа: самые дорогие узлы, full scans по крупным таблицам, ошибки оценки (planner vs. реальность — нужен `analyze=true` на PG), рискованные Nested Loop с большим внешним входом, спиллы сортировки на диск. PG: `EXPLAIN (FORMAT JSON)` / `EXPLAIN ANALYZE`. Oracle: `EXPLAIN PLAN` + `PLAN_TABLE` (`analyze` игнорируется — Oracle даёт только статический план). Параметры можно передавать как `params` (`?`) или `namedParams` (`:name`) |
| `validateQuery` | Проверка синтаксиса без выполнения: guard + `prepareStatement` в драйвере. Параметры можно передавать как `params` (`?`) или `namedParams` (`:name`). Полезно для самокоррекции LLM |

### Замеры

Инструменты для измерения реальной стоимости запроса — чтобы LLM не гадал по плану, а видел
настоящие миллисекунды и счётчики буферов.

| Tool | Описание |
|---|---|
| `benchmarkQuery` | Прогнать запрос `coldRuns + warmRuns` раз (по умолчанию 1 cold + 3 warm) и вернуть wall-clock `min` / `median` / `max` для warm-прогонов (cold — отдельно). Параметры можно передавать как `params` (`?`) или `namedParams` (`:name`). `limit` и `timeoutSeconds` **обязательны** — безлимитные запросы отклоняются. Возвращает размер последнего результата (row_count, columns, truncated), но не сами строки |
| `timedQuery` | Обычный `executeQuery` + `elapsed_ms` по wall-clock. Параметры можно передавать как `params` (`?`) или `namedParams` (`:name`). На PostgreSQL дополнительно снимается snapshot `pg_stat_statements` до и после запроса; diff показывает, какие queryid прибавили `calls` / `total_exec_time_ms` / `rows` / `shared_blks_hit` / `shared_blks_read` — видно, на что сервер реально потратил время. Требует установленного `pg_stat_statements` (`CREATE EXTENSION pg_stat_statements;` + `shared_preload_libraries`); если расширения нет — поле `pg_stat_statements.available: false` |

### Метаданные

| Tool | Описание |
|---|---|
| `listSchemas` | Список схем. Системные скрыты по умолчанию, `includeSystem=true` — показать все |
| `listTables` | Таблицы/представления в схеме. Параметры: `schema`, `namePattern` (с `%` / `_`), `types` (через запятую: `TABLE,VIEW,MATERIALIZED VIEW`) |
| `describeTable` | Полное описание объекта одним вызовом: колонки (тип, размер, nullable, default, remarks), PK, уникальные, индексы, исходящие FK, входящие FK |
| `getViewDefinition` | SQL-определение представления |
| `listRoutines` | Функции / процедуры / пакеты в схеме |
| `getRoutineDefinition` | Исходник функции/процедуры (для Oracle — все строки `ALL_SOURCE` склеенные по порядку) |
| `listSequences` | Последовательности в схеме |
| `searchObjects` | Поиск по имени (case-insensitive, substring) среди всех non-system объектов — таблиц, представлений, функций, последовательностей |

### Контекст схемы

Высокоуровневые инструменты для быстрой ориентации в схеме и построения SQL. Вместо того чтобы
вручную вызывать `listTables` → `describeTable` → `sampleRows` для каждой таблицы, LLM может
одним вызовом получить готовый контекст: таблицы, колонки, связи, ограничения, примеры строк.

| Tool | Описание |
|---|---|
| `schemaOverview` | Компактный снимок схемы для написания SQL: таблицы/вью, колонки, PK, FK, индексы и рёбра связей. Параметры: `schema`, `namePattern` (с `%` / `_`), `includeViews`, `includeStats`, `includeInferred` (связи по `*_id`), `maxTables` (по умолчанию 50, макс 300) |
| `tableContext` | Контекст вокруг одной таблицы: сама таблица, FK-родители, опционально — дочерние таблицы и рёбра связей. Обход FK на заданную глубину (по умолчанию 1, макс 4). Параметры: `schema`, `table`, `depth`, `includeIncoming`, `includeStats`, `includeInferred`, `inferredScanLimit` (по умолчанию 300, макс 300 — сколько таблиц схемы сканировать для inferred-связей; нужен только при `includeInferred=true`) |
| `findJoinPaths` | Поиск путей JOIN между двумя таблицами по FK. Граф обходится в обоих направлениях, каждое ребро содержит `joinCondition`. Параметры: `fromSchema`/`fromTable`, `toSchema`/`toTable`, `maxDepth` (по умолчанию 4), `maxPaths` (по умолчанию 5), `scanLimit` (по умолчанию 300, макс 300), `includeInferred` |
| `schemaBrief` | Компактная текстовая сводка схемы: hub-таблицы, fact/detail, lookup/reference, ключевые связи, enum-like CHECK-колонки, подозрительные неявные JOIN и краткие заметки по таблицам. Удобно, когда полный JSON был бы слишком объёмным |
| `schemaGraph` | Метрики графа связей схемы: узлы с входящей/исходящей степенью и классификацией, рёбра, центральные таблицы, изолированные таблицы, компоненты связности, намёки на циклы. Опционально — кратчайший путь между двумя таблицами |
| `schemaLint` | Линт-аудит схемы: отсутствующие PK, FK без индексов, несоответствие типов FK, inferred-но-не-declared связи, nullable unique, status/type без CHECK, сиротские `*_id`, отсутствующие remarks, изолированные таблицы, широкие таблицы. Набор проверок настраивается через `checks` |
| `queryContext` | Построение компактного контекста для написания SQL по поисковым терминам и/или явно указанным таблицам. Находит релевантные таблицы и колонки, включает ограничения и allowed values, связи и пути JOIN между выбранными таблицами, опционально — примеры строк (до 3 на таблицу) |
| `schemaGraphDot` | DOT/Graphviz-представление графа связей схемы. Узлы — таблицы со всеми колонками и типами (PK помечены 🔑, FK — →), рёбра — с условиями JOIN. Связи declared FK — сплошные линии, inferred `*_id` — пунктирные серые. Параметры: `schema`, `tables` (опциональный фильтр через запятую), `includeInferred` |

### Snapshot / кэш метаданных

Структурные метаданные (колонки, ключи, индексы, FK, constraints, triggers) кэшируются в памяти
с TTL — это ускоряет повторные вызовы `schemaOverview`, `tableContext`, `findJoinPaths`,
`schemaLint`, `schemaGraph`, `queryContext` и `describeTable`. Стат-инструменты (`tableStats`,
`indexStats`, `columnStats`, `sampleRows` и т. д.) **не** кэшируются — счётчики живые.

Конфигурация:
- `JDBC_METADATA_CACHE_TTL_SECONDS` — TTL в секундах. По умолчанию `300`. Поставьте `0`, чтобы отключить кэш.
- `JDBC_METADATA_CACHE_MAX_ENTRIES` — защитный потолок (по умолчанию `2000`); при превышении кэш сбрасывается целиком.

| Tool | Описание |
|---|---|
| `getSchemaSnapshot` | Мета-информация о кэше: TTL, hit/miss, сколько таблиц закэшировано в каждой схеме (только имена, не содержимое), список list-cache-записей. Параметр `schema` — фильтр |
| `refreshSchemaSnapshot` | Инвалидирует и сразу прогревает кэш. С `table` — только одна таблица; с `schema` — все таблицы схемы (`describeTable` для каждой); без аргументов — полный сброс без прогрева. Параметр `maxTables` ограничивает прогрев (по умолчанию 300) |
| `invalidateSnapshot` | Точечный сброс без прогрева: `table` → одна таблица; `schema` → вся схема; без аргументов → весь кэш |

### Исследование данных

| Tool | Описание |
|---|---|
| `sampleRows` | Несколько строк из таблицы/вью (`SELECT * FROM t LIMIT N`). Параметры: `schema`, `table`, `limit` (по умолчанию 10, макс 100) |
| `columnStats` | Базовая статистика колонки: `total_rows`, `non_null_rows`, `distinct_values`, `min`, `max` |

### Селективность и распределение (для оптимизации предикатов)

`columnStats` показывает только границы. Эти инструменты отвечают на вопросы «насколько
предикат избирателен?» и «насколько значения в колонке перекошены?» — то, без чего LLM не
может осмысленно выбрать индекс или переписать JOIN.

| Tool | Описание |
|---|---|
| `columnDistribution` | Топ-N самых частых значений колонки + их доля. Показывает перекос (`70% строк со status='OK'` — индекс по `status` без других колонок бесполезен). Параметры: `schema`, `table`, `column`, `topN` (по умолчанию 20, макс 1000) |
| `columnHistogram` | Перцентили P25 / P50 / P75 / P90 / P95 / P99 + `min`, `max`, null-счётчик. Использует SQL:2003 `WITHIN GROUP`: `percentile_cont` для числовых типов, `percentile_disc` для всех остальных — работает и для дат/таймстемпов/текста |
| `nullRatio` | За один скан — null / non-null по всем колонкам таблицы. Колонки отсортированы по убыванию `null_ratio`. Флаг `sparse=true` для колонок с долей null > 50% (кандидат на partial index) |
| `estimateSelectivity` | Оценка числа строк, которые вернёт предикат, **без выполнения запроса** — через `EXPLAIN` на `SELECT 1 FROM t WHERE <предикат>`. Возвращает оценочные строки, базовое (без фильтра) число строк, selectivity. Полезно, чтобы поставить самый избирательный предикат первым в составном индексе |
| `joinCardinality` | Оценка числа строк на выходе `JOIN` — без выполнения. Даёт planner-оценку, строки каждой стороны, `selectivity_vs_cartesian`. Поддержка `INNER` / `LEFT` / `RIGHT` / `FULL` |

### Статистика объектов (оптимизация запросов)

Эти инструменты дают LLM масштаб и признаки здоровья объектов, без чего советы по оптимизации
превращаются в угадывание: данные достаются из системных каталогов (`pg_class`, `pg_stat_*`,
`ALL_TABLES`, `ALL_INDEXES`, `DBA_SEGMENTS`) и агрегируются на стороне Java.

| Tool | Описание |
|---|---|
| `tableStats` | Размер таблицы и индексов в байтах, оценочное количество строк, dead tuples (PG), last vacuum / analyze, счётчики seq/idx сканов. На Oracle дополнительно best-effort `DBA_SEGMENTS` (если доступно) |
| `indexStats` | По каждому индексу: размер, счётчик сканов, колонки, признак unique/primary, тип индекса. Extras для PG: `idx_tup_read/fetch`, `pg_get_indexdef`. Extras для Oracle: `distinct_keys`, `clustering_factor`, `blevel`, `leaf_blocks`, `last_analyzed` |
| `unusedIndexes` | Индексы с нулём сканов (PG: `pg_stat_user_indexes`). PK и UNIQUE индексы исключаются. Oracle: возвращает диагностическое сообщение (`ALL_INDEXES` не раскрывает счётчики — нужен `DBA_INDEX_USAGE` 12.2+ или `V$OBJECT_USAGE` с `ALTER INDEX ... MONITORING USAGE`) |
| `redundantIndexes` | Индексы, чей список колонок — строгий префикс другого индекса той же таблицы. Unique-индексы не репортятся (их удаление убирает ограничение). Тип индекса должен совпадать |
| `fkIndexCoverage` | Внешние ключи на дочерней стороне без поддерживающего индекса — классическая причина медленных `DELETE`/`UPDATE CASCADE` и медленных JOIN. В результате — готовый `suggested_index_columns` под `CREATE INDEX` |

Все инструменты **read-only** — данные не изменяются.

## Формат ошибок

Все tool-ы возвращают ошибки одной формой — JSON с полями `error` и `kind`:

```json
{"error": "Only SELECT / WITH / EXPLAIN statements are allowed", "kind": "rejected"}
```

| `kind` | Когда |
|---|---|
| `sql` | БД вернула `SQLException` (синтаксис, нет объекта, прав, ...). |
| `argument` | Неверный аргумент tool-а. |
| `rejected` | Read-only guard заблокировал запрос до отправки в БД. |
| `not_found` | `getViewDefinition` / `getRoutineDefinition` / `getTriggerDefinition` ничего не нашёл. В теле также `missing` и `name`. |
| `driver` / `unexpected` / `plan_parse` | Внутренние сбои драйвера / необработанные / парсинг плана. |

`validateQuery` использует свою форму (без `kind` — дискриминатор `valid`):

```json
{"valid": true,  "parameters": 1, "columns": 3}
{"valid": false, "stage": "guard|params|driver", "error": "..."}
```

## Защита от записи (read-only)

Защита многоуровневая, рассчитана в первую очередь на «случайные» `DELETE`/`DROP` от LLM,
не на злоумышленника (злоумышленнику у вас и так есть URL, логин и пароль).

1. **ReadOnlyGuard (в нашем коде).** Перед отправкой в БД проверяем, что первый значимый
   токен — это `SELECT`, `WITH` или `EXPLAIN`. Отклоняем multi-statement (точки с запятой
   между операторами). Комментарии игнорируются. Это 99% защиты.
2. **Флаг `connection.setReadOnly(true)`.** Ставится Hikari и нами при каждом чекауте.
3. **PostgreSQL: `default_transaction_read_only=on`** добавляется в JDBC URL автоматически
   (если вы не указали свой `options=`). Даже DDL на сервере будет отклонён.
4. **Oracle: `SET TRANSACTION READ ONLY`** выполняется перед каждым пользовательским
   запросом. DDL в Oracle автокоммитится в обход транзакции — от него защищает только guard.

### Максимальная защита: read-only пользователь в БД

Если вы готовы потратить 5 минут — создайте специального пользователя с правами
только на чтение. Это железная гарантия, даже если вы случайно отключите guard.

**PostgreSQL:**
```sql
CREATE ROLE ai_readonly LOGIN PASSWORD 'strong-password';
GRANT CONNECT ON DATABASE mydb TO ai_readonly;
GRANT USAGE ON SCHEMA public TO ai_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ai_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT ON TABLES TO ai_readonly;
```

**Oracle:**
```sql
CREATE USER ai_readonly IDENTIFIED BY "strong-password";
GRANT CREATE SESSION TO ai_readonly;
GRANT SELECT ANY DICTIONARY TO ai_readonly;  -- для метаданных
-- Для каждой нужной таблицы/вью:
GRANT SELECT ON app_schema.customers TO ai_readonly;
-- ... или роль, собирающая все SELECT'ы:
-- CREATE ROLE ai_ro_role; GRANT ai_ro_role TO ai_readonly;
```

### Отключение guard

Если вам нужно, например, вызывать stored procedure с read-only семантикой, которую guard не
пропускает, можно отключить client-side проверку:

```
JDBC_READONLY_GUARD=off
```

Флаги уровня соединения (`setReadOnly`, `default_transaction_read_only`, `SET TRANSACTION READ ONLY`)
при этом остаются включёнными.

## Стек

- Java 25, Spring Boot 4.0, Spring AI MCP 2.0.0-M3 (stdio transport)
- HikariCP (через Spring Boot starter-jdbc)
- PostgreSQL JDBC 42.7.4
- Oracle JDBC ojdbc11 23.6.0.24.10
- Gradle 9.3.1 с version catalog

## Сборка

```bash
# Указать JDK 25+, если не является JDK по умолчанию:
export JAVA_HOME="$HOME/.jdks/jdk-25.0.2"

./gradlew build
```

Результат: `build/libs/jdbc-mcp-server.jar` (~32 MB, включает оба драйвера).

### Интеграционные тесты

Интеграционные тесты поднимают реальные PostgreSQL и Oracle Free через Testcontainers
(нужен Docker). Они исключены из обычной сборки и запускаются отдельно:

```bash
./gradlew integrationTest
```

> Первый запуск Oracle Free качает ~1.5 GB образ и стартует несколько минут.

### Smoke-тесты против реальной Oracle

Если есть доступ к уже поднятой Oracle — можно прогнать read-only smoke-тесты
(`LiveOracleIntegrationTest`) прямо против неё. Тесты выполняют только `SELECT`
по словарю (`DUAL`, `ALL_TABLES`) и по схеме пользователя — никаких CREATE/INSERT/UPDATE.

Логин и пароль **не хранятся** в репозитории — передаются через переменные окружения.
Если они не заданы — тесты тихо скипаются, ничего не ломают в обычной сборке.

```bash
export LIVE_ORACLE_URL='jdbc:oracle:thin:@db.example.com:1521:ORCL'
export LIVE_ORACLE_USERNAME='ai_readonly'
export LIVE_ORACLE_PASSWORD='secret'
# опционально, по умолчанию = LIVE_ORACLE_USERNAME в верхнем регистре:
# export LIVE_ORACLE_SCHEMA='APP_SCHEMA'

./gradlew liveOracleTest
```

Windows (PowerShell):

```powershell
$env:LIVE_ORACLE_URL      = 'jdbc:oracle:thin:@db.example.com:1521:ORCL'
$env:LIVE_ORACLE_USERNAME = 'ai_readonly'
$env:LIVE_ORACLE_PASSWORD = 'secret'
./gradlew liveOracleTest
```

Файл `.env` добавлен в `.gitignore` — при желании храните переменные там и подгружайте
их перед запуском (например, через `direnv`, `dotenv-cli` или `set -a; . ./.env; set +a`
в bash). Сам Gradle `.env` не парсит — переменные должны быть в окружении на момент запуска.

## Настройка

| Переменная | Обязательна | Описание |
|---|---|---|
| `JDBC_URL` | да | JDBC URL, например `jdbc:postgresql://host:5432/db` или `jdbc:oracle:thin:@//host:1521/service` |
| `JDBC_USERNAME` | да | Пользователь БД (предпочтительно — read-only) |
| `JDBC_PASSWORD` | да | Пароль |
| `JDBC_DEFAULT_SCHEMA` | нет | Схема по умолчанию для metadata-инструментов. Если не задано — используется текущая схема соединения |
| `JDBC_QUERY_TIMEOUT_SECONDS` | нет | Таймаут на каждый запрос, по умолчанию `30` |
| `JDBC_MAX_ROWS` | нет | Максимум строк в одном ответе, по умолчанию `1000`. При превышении в ответ добавляется `truncated: true` |
| `JDBC_FETCH_SIZE` | нет | JDBC `fetchSize`, по умолчанию `500` |
| `JDBC_READONLY_GUARD` | нет | `strict` (по умолчанию) или `off` |

Тип БД определяется автоматически по префиксу URL (`jdbc:postgresql:` → PostgreSQL,
`jdbc:oracle:` → Oracle).

### Примеры URL

```
jdbc:postgresql://db.example.com:5432/myapp
jdbc:postgresql://db.example.com:5432/myapp?currentSchema=public&sslmode=require

jdbc:oracle:thin:@//db.example.com:1521/ORCLPDB1
jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=...)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=...)))
```

## Запуск

```bash
JDBC_URL=jdbc:postgresql://db.example.com:5432/myapp \
JDBC_USERNAME=ai_readonly \
JDBC_PASSWORD=secret \
  java -jar build/libs/jdbc-mcp-server.jar
```

Сервер сразу начнёт слушать MCP через stdin/stdout. На stderr пишутся логи.

## Подключение к AI-клиенту

Добавить в конфигурацию клиента:

```json
{
  "command": "java",
  "args": ["-jar", "<абсолютный-путь>/jdbc-mcp-server.jar"],
  "env": {
    "JDBC_URL": "jdbc:postgresql://db.example.com:5432/myapp",
    "JDBC_USERNAME": "ai_readonly",
    "JDBC_PASSWORD": "secret"
  }
}
```

Куда именно:

| Клиент | Способ подключения |
|---|---|
| Claude Code | `claude mcp add --scope user -e JDBC_URL=... -e JDBC_USERNAME=... -e JDBC_PASSWORD=... -- jdbc java -jar /path/to/jdbc-mcp-server.jar` |
| Qwen Code | `~/.qwen/settings.json` -> `"mcpServers"` -> `"jdbc"` |
| VS Code | `.vscode/mcp.json` -> `"servers"` -> `"jdbc"` |
| Cursor | `.cursor/mcp.json` -> `"mcpServers"` -> `"jdbc"` |
| Claude Desktop | `claude_desktop_config.json` -> `"mcpServers"` -> `"jdbc"` |

Для Claude Code без `--scope user` сервер добавится только для текущего проекта.
Проверить подключение: `claude mcp list`. После добавления перезапустить клиент.

Если у вас одновременно разные базы на разных проектах — добавьте два сервера с разными
ключами (`jdbc-pg`, `jdbc-oracle`) и разным набором env-переменных.

## Структура проекта

```
├── src/main/java/ru/it_spectrum/ai/jdbc/mcp/
│   ├── JdbcMcpServerApplication.java   — точка входа Spring Boot
│   ├── config/
│   │   ├── JdbcProperties.java         — параметры подключения из env
│   │   ├── DatabaseKind.java           — автодетект PG/Oracle по URL
│   │   └── DataSourceConfig.java       — Hikari + read-only на уровне соединения
│   ├── dialect/
│   │   ├── SqlDialect.java             — интерфейс диалекта
│   │   ├── PostgresDialect.java        — EXPLAIN, pg_catalog, pg_get_viewdef
│   │   ├── OracleDialect.java          — EXPLAIN PLAN, ALL_VIEWS, ALL_SOURCE, SET TRANSACTION READ ONLY
│   │   └── DialectConfig.java          — выбор реализации по DatabaseKind
│   ├── sql/
│   │   ├── ReadOnlyGuard.java          — простой проверщик первого токена
│   │   ├── SqlNotAllowedException.java
│   │   ├── QueryResult.java            — структура результата
│   │   ├── SqlExecutor.java            — исполнение запросов с лимитами
│   │   └── BenchmarkService.java       — benchmark (cold+warm) и timed (+ pg_stat_statements diff)
│   ├── metadata/
│   │   ├── MetadataService.java        — DatabaseMetaData + dialect-specific
│   │   ├── StatsService.java           — table/index stats, FK coverage, redundant/unused indexes
│   │   ├── DistributionService.java    — column distribution / histogram / null ratio / selectivity / join cardinality
│   │   └── SchemaContextService.java   — high-level schema context: overview, table context, join paths, graph, lint, brief, query context
│   ├── plan/
│   │   ├── ParsedPlan.java / PlanNode.java — единая модель плана (engine-agnostic)
│   │   ├── PlanParser.java             — интерфейс парсера
│   │   ├── PostgresPlanParser.java     — JSON EXPLAIN → дерево
│   │   ├── OraclePlanParser.java       — PLAN_TABLE → дерево
│   │   ├── PlanAnalyzer.java           — сводка: expensive / full scan / est error / nested loop / spill
│   │   └── JsonReader.java             — тонкий парсер JSON без зависимостей
│   ├── format/
│   │   ├── OutputFormat.java
│   │   └── ResultFormatter.java        — JSON / Markdown / CSV
│   └── tools/
│       ├── QueryTools.java             — executeQuery, explainQuery, analyzePlan, validateQuery
│       ├── MetadataTools.java          — schemas / tables / describe / view / routines / sequences / search
│       ├── SampleTools.java            — sampleRows, columnStats
│       ├── DistributionTools.java      — columnDistribution, columnHistogram, nullRatio, estimateSelectivity, joinCardinality
│       ├── StatsTools.java             — tableStats, indexStats, unusedIndexes, redundantIndexes, fkIndexCoverage
│       ├── BenchmarkTools.java         — benchmarkQuery, timedQuery
│       └── SchemaContextTools.java     — schemaOverview, tableContext, findJoinPaths, schemaLint, schemaBrief, schemaGraph, queryContext, schemaGraphDot
└── src/main/resources/
    ├── application.yml                 — MCP stdio + JDBC properties
    └── logback-spring.xml              — логи в stderr (stdout занят MCP)
```

## Troubleshooting

- **"Cannot find a Java installation ... matching languageVersion=25"** — установите JDK 25+
  и выставьте `JAVA_HOME`. Gradle toolchain без подключения к интернету не сможет его скачать.
- **Connection refused / ORA-01017 / FATAL: password authentication failed** — проверьте
  `JDBC_URL`, `JDBC_USERNAME`, `JDBC_PASSWORD`. Для PG можно потестить
  `psql "$JDBC_URL"`, для Oracle — `sqlplus $JDBC_USERNAME/$JDBC_PASSWORD@...`.
- **`{"kind":"rejected","error":"Only SELECT / WITH / EXPLAIN statements are allowed"}`** — guard сработал.
  Это ожидаемое поведение для любых write-операций. Если запрос действительно read-only
  (например, вызов read-only функции через `SELECT func(...)`) — он пройдёт. Для полностью
  нетривиальных случаев можно выключить guard через `JDBC_READONLY_GUARD=off`.
- **Oracle: `ORA-01456: may not perform insert/delete/update operation inside a READ ONLY transaction`** —
  это тоже ожидаемо, если кто-то попытался write-операцию мимо guard. Значит, защита работает.
- **Пустой результат `describeTable` / `listTables` на Oracle** — имена объектов в Oracle
  хранятся в верхнем регистре. Передавайте `CUSTOMERS`, а не `customers`.
