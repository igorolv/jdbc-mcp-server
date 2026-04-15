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
1. вызывает `listSchemas` / `listTables` / `describeTable` — видит реальную структуру;
2. пишет запрос и вызывает `validateQuery` — проверяет синтаксис без выполнения;
3. при необходимости вызывает `explainQuery` — смотрит план;
4. вызывает `executeQuery` — получает данные.

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
| `executeQuery` | Выполнить `SELECT` / `WITH` / `EXPLAIN`. Параметры: `sql`, `params` (массив для `?`), `limit`, `timeoutSeconds`, `format` (`json` по умолчанию, `markdown`, `csv`). Результат помечается `truncated: true`, если превысил лимит |
| `explainQuery` | План выполнения запроса. PG: `EXPLAIN (FORMAT TEXT)`. Oracle: `EXPLAIN PLAN FOR` + `DBMS_XPLAN.DISPLAY`. Флаг `analyze=true` (PG) включает `EXPLAIN ANALYZE` — осторожно, запрос реально выполнится |
| `validateQuery` | Проверка синтаксиса без выполнения: guard + `prepareStatement` в драйвере. Полезно для самокоррекции LLM |

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

### Исследование данных

| Tool | Описание |
|---|---|
| `sampleRows` | Несколько строк из таблицы/вью (`SELECT * FROM t LIMIT N`). Параметры: `schema`, `table`, `limit` (по умолчанию 10, макс 100) |
| `columnStats` | Базовая статистика колонки: `total_rows`, `non_null_rows`, `distinct_values`, `min`, `max` |

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
│   │   └── SqlExecutor.java            — исполнение запросов с лимитами
│   ├── metadata/
│   │   ├── MetadataService.java        — DatabaseMetaData + dialect-specific
│   │   └── StatsService.java           — table/index stats, FK coverage, redundant/unused indexes
│   ├── format/
│   │   ├── OutputFormat.java
│   │   └── ResultFormatter.java        — JSON / Markdown / CSV
│   └── tools/
│       ├── QueryTools.java             — executeQuery, explainQuery, validateQuery
│       ├── MetadataTools.java          — schemas / tables / describe / view / routines / sequences / search
│       ├── SampleTools.java            — sampleRows, columnStats
│       └── StatsTools.java             — tableStats, indexStats, unusedIndexes, redundantIndexes, fkIndexCoverage
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
- **`Rejected: Only SELECT / WITH / EXPLAIN statements are allowed`** — guard сработал.
  Это ожидаемое поведение для любых write-операций. Если запрос действительно read-only
  (например, вызов read-only функции через `SELECT func(...)`) — он пройдёт. Для полностью
  нетривиальных случаев можно выключить guard через `JDBC_READONLY_GUARD=off`.
- **Oracle: `ORA-01456: may not perform insert/delete/update operation inside a READ ONLY transaction`** —
  это тоже ожидаемо, если кто-то попытался write-операцию мимо guard. Значит, защита работает.
- **Пустой результат `describeTable` / `listTables` на Oracle** — имена объектов в Oracle
  хранятся в верхнем регистре. Передавайте `CUSTOMERS`, а не `customers`.
