# spring-boot-starter-duckdb

There is no Spring Boot starter for DuckDB on Maven Central. The JDBC driver is there, but wiring a `DataSource`, getting the connection model right, and adding health checks is left to you.

```xml
<dependency>
  <groupId>io.github.mehrabr</groupId>
  <artifactId>spring-boot-starter-duckdb</artifactId>
  <version>1.0.0</version>
</dependency>
```

No configuration required. Defaults give you an in-memory DuckDB with a single pooled connection. Point it at a file or MotherDuck with `application.yml`.

## Usage

```yaml
# File-backed, read-write
duckdb:
  path: /data/myapp.db

# Read-only
duckdb:
  path: /data/myapp.db
  read-only: true

# MotherDuck
duckdb:
  motherduck:
    enabled: true
    database: my_db
    # token: your-token  — or set MOTHERDUCK_TOKEN environment variable
```

## Read-write mode

DuckDB in read-write mode doesn't support multiple independent JDBC connections to the same file. Opening the same path twice with `DriverManager.getConnection()` will crash the JVM — not throw an exception, crash it. All connections must be derived from a single root connection via `DuckDBConnection#duplicate()`.

The starter handles this automatically. You get a `DuckDBConnectionPool` backed by a `BlockingQueue` of duplicated connections. The default pool size is 1 because DuckDB serializes writes internally anyway. Raise `duckdb.pool.max-size` if you have readers that can run concurrently without conflicting.

```yaml
duckdb:
  path: /data/myapp.db
  pool:
    max-size: 4
    connection-timeout-ms: 30000
```

## Read-only mode

Multiple processes may hold the same file open in read-only mode. Standard HikariCP pooling is safe here, and that's what the starter uses for this path.

## MotherDuck

Cloud connections, standard HikariCP. The token resolves from `duckdb.motherduck.token` first, then the `MOTHERDUCK_TOKEN` environment variable. The env var is the right choice for containers and CI where you don't want secrets in config files.

## Health indicator

Add `spring-boot-starter-actuator` and a health indicator registers automatically at `/actuator/health`. Nothing happens if you don't have actuator.

```json
{
  "status": "UP",
  "details": {
    "version": "v1.1.3"
  }
}
```

## Configuration reference

| Property | Default | Description |
|---|---|---|
| `duckdb.path` | _(empty)_ | Database file path. Empty means in-memory. |
| `duckdb.read-only` | `false` | Open in read-only mode. Uses HikariCP. |
| `duckdb.motherduck.enabled` | `false` | Use MotherDuck. |
| `duckdb.motherduck.token` | _(env)_ | Auth token. Falls back to `MOTHERDUCK_TOKEN`. |
| `duckdb.motherduck.database` | _(empty)_ | MotherDuck database name. |
| `duckdb.pool.max-size` | `1` | Connections in the read-write pool. |
| `duckdb.pool.connection-timeout-ms` | `30000` | Wait time before throwing on pool exhaustion. |
| `duckdb.pool.idle-timeout-ms` | `600000` | Idle connection timeout (HikariCP modes). |

## Overriding

Every bean is `@ConditionalOnMissingBean`. Define your own `DataSource` and the starter backs off entirely.

## Publishing to Maven Central

Requires a GPG key and a Sonatype Central Portal token. Local test deploy:

```
mvn clean deploy -DskipTests
```

The deployment appears in [central.sonatype.com](https://central.sonatype.com) under Deployments. Click Publish manually for the first release, then update `autoPublish` in `pom.xml` once you're confident in the release workflow.

The GitHub Actions release workflow triggers on `v*.*.*` tags. Secrets needed: `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`, `SONATYPE_TOKEN_USERNAME`, `SONATYPE_TOKEN_PASSWORD`.

## License

Apache 2.0
