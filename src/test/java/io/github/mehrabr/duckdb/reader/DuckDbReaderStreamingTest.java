package io.github.mehrabr.duckdb.reader;

import io.github.mehrabr.duckdb.DuckDBAutoConfiguration;
import io.github.mehrabr.duckdb.DuckDBMetricsAutoConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDbReaderStreamingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DuckDBAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    DuckDBMetricsAutoConfiguration.class));

    // ── Stream correctness ────────────────────────────────────────────────────

    @Test
    void streamReturnsAllRows(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("rows.csv");
        Files.writeString(csv, "n\n1\n2\n3\n4\n5\n");

        runner.run(ctx -> {
            DuckDbReader reader = ctx.getBean(DuckDbReader.class);
            List<Integer> nums = new ArrayList<>();
            try (Stream<Integer> stream = reader.sql("SELECT n FROM read_csv(:p)")
                    .param("p", csv.toString())
                    .stream((rs, i) -> rs.getInt("n"))) {
                stream.forEach(nums::add);
            }
            assertThat(nums).containsExactly(1, 2, 3, 4, 5);
        });
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    /**
     * With pool size 1, a closed stream must return its connection so subsequent
     * operations can proceed. If the connection is not returned the next acquire
     * will time out.
     */
    @Test
    void closedStreamReleasesConnectionToPool(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("t.csv");
        Files.writeString(csv, "v\n42\n");

        runner.withPropertyValues("duckdb.pool.max-size=1")
                .run(ctx -> {
                    DuckDbReader reader = ctx.getBean(DuckDbReader.class);

                    try (Stream<Integer> s = reader.sql("SELECT v FROM read_csv(:p)")
                            .param("p", csv.toString())
                            .stream((rs, i) -> rs.getInt("v"))) {
                        s.forEach(v -> {});
                    }

                    // If stream didn't release the connection, this would time out.
                    DataSource ds = ctx.getBean(DataSource.class);
                    try (Connection conn = ds.getConnection();
                         var stmt = conn.createStatement();
                         var rs = stmt.executeQuery("SELECT 1")) {
                        assertThat(rs.next()).isTrue();
                    }
                });
    }

    /**
     * Stream can be consumed partially and still release its connection on close.
     */
    @Test
    void earlyStreamCloseReleasesConnection(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("big.csv");
        StringBuilder sb = new StringBuilder("n\n");
        for (int i = 1; i <= 100; i++) sb.append(i).append("\n");
        Files.writeString(csv, sb.toString());

        runner.withPropertyValues("duckdb.pool.max-size=1")
                .run(ctx -> {
                    DuckDbReader reader = ctx.getBean(DuckDbReader.class);

                    try (Stream<Integer> s = reader.sql("SELECT n FROM read_csv(:p)")
                            .param("p", csv.toString())
                            .stream((rs, i) -> rs.getInt("n"))) {
                        s.limit(3).forEach(v -> {});
                    }

                    DataSource ds = ctx.getBean(DataSource.class);
                    try (Connection conn = ds.getConnection();
                         var stmt = conn.createStatement();
                         var rs = stmt.executeQuery("SELECT 42")) {
                        assertThat(rs.next()).isTrue();
                    }
                });
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @Test
    void streamOpenMetricIncrements(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("m.csv");
        Files.writeString(csv, "x\n1\n");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        runner.withBean(SimpleMeterRegistry.class, () -> registry)
                .run(ctx -> {
                    DuckDbReader reader = ctx.getBean(DuckDbReader.class);

                    try (Stream<Integer> s = reader.sql("SELECT x FROM read_csv(:p)")
                            .param("p", csv.toString())
                            .stream((rs, i) -> rs.getInt("x"))) {
                        s.forEach(v -> {});
                    }

                    Counter openCounter = registry.find("duckdb.reader.stream.open").counter();
                    assertThat(openCounter).isNotNull();
                    assertThat(openCounter.count()).isEqualTo(1.0);
                });
    }

    @Test
    void readerQueryMetricIncrements(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("q.csv");
        Files.writeString(csv, "y\nhello\n");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        runner.withBean(SimpleMeterRegistry.class, () -> registry)
                .run(ctx -> {
                    DuckDbReader reader = ctx.getBean(DuckDbReader.class);
                    reader.read(Source.csv(csv.toString()), (rs, i) -> rs.getString("y"));

                    Counter queryCounter = registry.find("duckdb.reader.query")
                            .tag("source", "csv").tag("location", "local").counter();
                    assertThat(queryCounter).isNotNull();
                    assertThat(queryCounter.count()).isEqualTo(1.0);
                });
    }

    @Test
    void connectionAcquiredMetricIncrements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        runner.withBean(SimpleMeterRegistry.class, () -> registry)
                .run(ctx -> {
                    DataSource ds = ctx.getBean(DataSource.class);
                    try (Connection conn = ds.getConnection();
                         var rs = conn.createStatement().executeQuery("SELECT 1")) {
                        rs.next();
                    }

                    Counter acquired = registry.find("duckdb.connection.acquired")
                            .tag("mode", "memory").counter();
                    assertThat(acquired).isNotNull();
                    assertThat(acquired.count()).isGreaterThanOrEqualTo(1.0);
                });
    }
}
