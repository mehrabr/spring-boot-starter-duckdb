package io.github.mehrabr.duckdb.reader;

import io.github.mehrabr.duckdb.DuckDBAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuckDbReaderTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DuckDBAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class));

    // ── CSV ─────────────────────────────────────────────────────────────────

    @Test
    void readCsvWithHeader(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("people.csv");
        Files.writeString(csv, "name,age\nAlice,30\nBob,25\n");

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            List<String> names = reader.read(
                    Source.csv(csv.toString(), CsvOptions.builder().header(true).build()),
                    (rs, i) -> rs.getString("name"));
            assertThat(names).containsExactly("Alice", "Bob");
        });
    }

    @Test
    void readCsvWithGlob(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.csv"), "n\n1\n2\n");
        Files.writeString(dir.resolve("b.csv"), "n\n3\n4\n");

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            List<Integer> nums = reader.read(
                    Source.csv(dir.resolve("*.csv").toString()),
                    (rs, i) -> rs.getInt("n"));
            assertThat(nums).containsExactlyInAnyOrder(1, 2, 3, 4);
        });
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    @Test
    void readJson(@TempDir Path dir) throws Exception {
        Path json = dir.resolve("data.json");
        Files.writeString(json, "[{\"id\":1,\"val\":\"x\"},{\"id\":2,\"val\":\"y\"}]");

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            List<String> vals = reader.read(
                    Source.json(json.toString()),
                    (rs, i) -> rs.getString("val"));
            assertThat(vals).containsExactly("x", "y");
        });
    }

    // ── Parquet ──────────────────────────────────────────────────────────────

    @Test
    void readParquet(@TempDir Path dir) throws Exception {
        Path parquet = dir.resolve("data.parquet");
        try (var conn = DriverManager.getConnection("jdbc:duckdb:");
             var stmt = conn.createStatement()) {
            stmt.execute("COPY (SELECT 1 AS id, 'hello' AS msg) TO '"
                    + parquet + "' (FORMAT PARQUET)");
        }

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            List<String> msgs = reader.read(
                    Source.parquet(parquet.toString()),
                    (rs, i) -> rs.getString("msg"));
            assertThat(msgs).containsExactly("hello");
        });
    }

    // ── sql() builder ────────────────────────────────────────────────────────

    @Test
    void sqlBuilderWithNamedParams(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("sales.csv");
        Files.writeString(csv, "region,total\nEast,100\nWest,200\nNorth,50\n");

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            List<String> top = reader.sql("""
                    SELECT region FROM read_csv(:p, header=true)
                    ORDER BY total DESC LIMIT :n
                    """)
                    .param("p", csv.toString())
                    .param("n", 2)
                    .query((rs, i) -> rs.getString("region"));
            assertThat(top).containsExactly("West", "East");
        });
    }

    @Test
    void sqlBuilderQueryOne(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("single.csv");
        Files.writeString(csv, "v\n42\n");

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            Optional<Integer> v = reader.sql("SELECT v FROM read_csv(:p)")
                    .param("p", csv.toString())
                    .queryOne((rs, i) -> rs.getInt("v"));
            assertThat(v).contains(42);
        });
    }

    @Test
    void sqlBuilderCount(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("rows.csv");
        Files.writeString(csv, "x\n1\n2\n3\n4\n5\n");

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            long count = reader.sql("SELECT count(*) FROM read_csv(:p)")
                    .param("p", csv.toString())
                    .count();
            assertThat(count).isEqualTo(5L);
        });
    }

    // ── Path safety (ADR-6) ──────────────────────────────────────────────────

    @Test
    void maliciousPathIsNotExecuted(@TempDir Path dir) throws Exception {
        // Create a table to verify it is NOT dropped by an injected path
        runner.run(ctx -> {
            var ds = ctx.getBean(javax.sql.DataSource.class);
            try (var conn = ds.getConnection(); var stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE sentinel (id INTEGER)");
                stmt.execute("INSERT INTO sentinel VALUES (1)");
            }

            DuckDbReader reader = ctx.getBean(DuckDbReader.class);

            // A path with SQL-injection syntax should produce a read error, not execute DROP
            assertThatThrownBy(() ->
                    reader.read(Source.csv("'); DROP TABLE sentinel; --"), (rs, i) -> rs.getInt(1)))
                    .isInstanceOf(Exception.class);

            // sentinel table must still exist
            try (var conn = ds.getConnection();
                 var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT count(*) FROM sentinel")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        });
    }

    // ── Auto-configuration back-off ──────────────────────────────────────────

    @Test
    void readerBeanRegistered() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(DuckDbReader.class));
    }

    @Test
    void readerBacksOffWhenCustomBeanPresent() {
        runner.withBean(DuckDbReader.class, () -> new DuckDbReader() {
                    @Override public <T> List<T> read(Source s, org.springframework.jdbc.core.RowMapper<T> m) { return List.of(); }
                    @Override public Query sql(String sql) { return null; }
                })
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DuckDbReader.class);
                    assertThat(ctx.getBean(DuckDbReader.class)).isNotInstanceOf(DefaultDuckDbReader.class);
                });
    }
}
