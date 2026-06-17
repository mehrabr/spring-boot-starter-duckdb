# spring-boot-starter-duckdb

## The single-writer rule — read this first

DuckDB allows exactly **one writer per file**. Opening the same file twice for writing with `DriverManager.getConnection()` does not throw an exception — **it segfaults the JVM**. The only safe way to share a read-write DuckDB file across threads is to open one root connection and derive every further connection via `DuckDBConnection#duplicate()`.

This library exists to make that invariant automatic. You get a `DataSource` backed by a `BlockingQueue` of pre-duplicated connections. Every connection your application borrows descends from a single root. You cannot accidentally open a second independent connection to the same file.

```xml
<dependency>
  <groupId>io.github.mehrabr</groupId>
  <artifactId>spring-boot-starter-duckdb</artifactId>
  <version>1.2.0</version>
</dependency>
```

No configuration required. Defaults give you an in-memory DuckDB with a single pooled connection.

---

## Usage

```yaml
# File-backed, read-write (uses DuckDBConnectionPool — single-writer safe)
duckdb:
  path: /data/myapp.db

# Read-only (HikariCP; multiple connections safe)
duckdb:
  path: /data/myapp.db
  read-only: true

# MotherDuck (HikariCP with md: JDBC URL)
duckdb:
  motherduck:
    enabled: true
    database: my_db
    # token: your-token  — or set MOTHERDUCK_TOKEN

# Query external Parquet/CSV/JSON from Spring
duckdb:
  extensions: [httpfs]
  s3:
    region: us-east-1
    access-key-id: ${AWS_ACCESS_KEY_ID}
    secret-access-key: ${AWS_SECRET_ACCESS_KEY}
```

---

## Read-write mode

`DuckDBConnectionPool` opens one root connection at startup and pre-fills a `BlockingQueue` with `pool.max-size` duplicated connections. `getConnection()` polls the queue with a timeout; `close()` returns the connection to the queue — no connection is ever independently opened to the file.

```yaml
duckdb:
  path: /data/myapp.db
  pool:
    max-size: 4                # default 1; raise for concurrent readers
    connection-timeout-ms: 30000
```

The default pool size is 1 because DuckDB serializes writes internally. Raise it only if you have read-heavy workloads that benefit from concurrency.

## Read-only mode

Multiple processes may hold the same file open read-only. Standard HikariCP pooling is safe here.

## Querying external files — `DuckDbReader`

Auto-configured whenever a DuckDB `DataSource` and `spring-jdbc` are present. Paths are **bound as JDBC parameters** — a malicious path cannot inject SQL.

```java
@Autowired DuckDbReader duck;

// Read a whole Parquet file
List<Sale> sales = duck.read(
    Source.parquet("/data/sales/*.parquet"),
    (rs, i) -> new Sale(rs.getString("region"), rs.getLong("total")));

// Projection with bound path and named params
List<RegionTotal> top = duck.sql("""
        SELECT region, sum(total) AS total
        FROM read_parquet(:path)
        GROUP BY region ORDER BY total DESC LIMIT :n
        """)
    .param("path", "s3://analytics/sales/*.parquet")
    .param("n", 10)
    .query((rs, i) -> new RegionTotal(rs.getString("region"), rs.getLong("total")));

// Stream a large file — ALWAYS use try-with-resources
try (Stream<Event> events = duck.sql("SELECT * FROM read_csv(:p)")
        .param("p", "/data/events.csv")
        .stream(Event::from)) {
    events.filter(Event::isError).forEach(alerter::push);
}
```

**Streaming deadlock warning:** `stream()` holds a pool connection until the stream is closed. With the default `pool.max-size: 1`, a single un-closed stream deadlocks the next acquire. Always close streams in try-with-resources.

### S3 / object storage

`httpfs` must be listed in `duckdb.extensions`. Credentials are written as a **temporary** DuckDB Secret on each pooled connection — they never appear in logs or the health endpoint.

```yaml
duckdb:
  extensions: [httpfs]
  s3:
    region: us-east-1
    endpoint: s3.amazonaws.com          # override for MinIO / R2 / Ceph
    url-style: vhost                    # vhost (default) | path (MinIO)
    use-credential-chain: false         # true = AWS provider chain
    access-key-id: ${AWS_ACCESS_KEY_ID:}
    secret-access-key: ${AWS_SECRET_ACCESS_KEY:}
```

### Extensions

Extensions in `duckdb.extensions` are `LOAD`-ed on **every** pooled connection (not just the root). `LOAD` is per-connection state; loading only the root is insufficient.

For airgapped installs, pre-provision extensions and set `duckdb.extension-directory` to the local directory.

## Health indicator

Add `spring-boot-starter-actuator` for automatic registration at `/actuator/health`:

```json
{
  "status": "UP",
  "details": { "version": "v1.5.3" }
}
```

## Micrometer metrics

Add `micrometer-core` for automatic emission:

| Metric | Tags | Purpose |
|---|---|---|
| `duckdb.connection.acquired` | `mode` | RW pool exercise |
| `duckdb.pool.wait` | `mode` | Pool contention |
| `duckdb.reader.query` | `source`, `location` | Read-side traffic |
| `duckdb.reader.stream.open` | — | Stream usage |
| `duckdb.reader.stream.leaked` | — | Unclosed streams |

## Configuration reference

| Property | Default | Description |
|---|---|---|
| `duckdb.path` | _(empty)_ | Database file path. Empty = in-memory. |
| `duckdb.read-only` | `false` | Open read-only (HikariCP). |
| `duckdb.extensions` | `[]` | Extensions to `LOAD` on every pooled connection. |
| `duckdb.extension-directory` | _(DuckDB default)_ | Pre-provisioned extension directory. |
| `duckdb.s3.region` | _(empty)_ | S3 region. |
| `duckdb.s3.endpoint` | `s3.amazonaws.com` | Override for S3-compatible stores. |
| `duckdb.s3.url-style` | `vhost` | `vhost` or `path`. |
| `duckdb.s3.use-credential-chain` | `false` | Use AWS credential provider chain. |
| `duckdb.s3.access-key-id` | _(env)_ | Prefer env-var indirection. |
| `duckdb.s3.secret-access-key` | _(env)_ | Prefer env-var indirection. |
| `duckdb.reader.default-stream-fetch-size` | _(driver default)_ | Chunk size for streaming reads. |
| `duckdb.pool.max-size` | `1` | Connections in the RW pool. |
| `duckdb.pool.connection-timeout-ms` | `30000` | Timeout before throwing on pool exhaustion. |
| `duckdb.pool.idle-timeout-ms` | `600000` | Idle connection timeout (HikariCP modes). |
| `duckdb.motherduck.enabled` | `false` | Use MotherDuck. |
| `duckdb.motherduck.token` | _(env)_ | Falls back to `MOTHERDUCK_TOKEN`. |
| `duckdb.motherduck.database` | _(empty)_ | MotherDuck database name. |

## Overriding

Every bean is `@ConditionalOnMissingBean`. Define your own `DataSource` and the starter backs off entirely. Define your own `DuckDbReader` to replace the auto-configured one.

## Non-goals

- **Hibernate/JPA dialect** — JPA's entity/transaction model fights OLAP; the impedance mismatch can't be fixed by a dialect. Use `JdbcTemplate`/`DuckDbReader` or jOOQ instead.
- **Typed SQL DSL** — jOOQ already ships an experimental DuckDB dialect. This library builds on `JdbcTemplate`, not against jOOQ.

## Support matrix

| starter | DuckDB JDBC | Spring Boot |
|---|---|---|
| 1.2.x | 1.5.3.0 | 3.4.x |
| 1.0.x | 1.1.3 | 3.3.x |

## MotherDuck

Cloud connections via standard HikariCP. The token resolves from `duckdb.motherduck.token` first, then `MOTHERDUCK_TOKEN`. Env-var indirection is the right choice for containers and CI.

## License

Apache 2.0
