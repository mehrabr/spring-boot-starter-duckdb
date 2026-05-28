package io.github.mehrabr.duckdb;

import com.zaxxer.hikari.HikariDataSource;
import io.github.mehrabr.duckdb.pool.DuckDBConnectionPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDBDataSourceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DuckDBAutoConfiguration.class));

    @Test
    void inMemoryDataSourceIsReadWritePool() {
        runner.run(ctx ->
                assertThat(ctx.getBean(DataSource.class)).isInstanceOf(DuckDBConnectionPool.class));
    }

    @Test
    void readWriteFileDataSourceIsPool(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("rw.db").toString();
        runner.withPropertyValues("duckdb.path=" + dbPath)
                .run(ctx ->
                        assertThat(ctx.getBean(DataSource.class)).isInstanceOf(DuckDBConnectionPool.class));
    }

    @Test
    void readOnlyFileDataSourceIsHikari(@TempDir Path tempDir) {
        String dbPath = tempDir.resolve("ro.db").toString();
        // Create the file first; the pool's close() is called on context shutdown, releasing the lock.
        runner.withPropertyValues("duckdb.path=" + dbPath).run(ctx -> {});
        runner.withPropertyValues("duckdb.path=" + dbPath, "duckdb.read-only=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
                });
    }

    @Test
    void concurrentAccessToReadWritePoolDoesNotCrash(@TempDir Path tempDir) throws Exception {
        String dbPath = tempDir.resolve("concurrent.db").toString();
        runner.withPropertyValues("duckdb.path=" + dbPath, "duckdb.pool.max-size=4")
                .run(ctx -> {
                    DataSource ds = ctx.getBean(DataSource.class);
                    int n = 4;
                    ExecutorService exec = Executors.newFixedThreadPool(n);
                    CountDownLatch ready = new CountDownLatch(n);
                    List<Future<Integer>> futures = new ArrayList<>();

                    for (int i = 0; i < n; i++) {
                        futures.add(exec.submit(() -> {
                            ready.countDown();
                            ready.await();
                            try (Connection conn = ds.getConnection();
                                 var stmt = conn.createStatement();
                                 var rs = stmt.executeQuery("SELECT 1")) {
                                rs.next();
                                return rs.getInt(1);
                            }
                        }));
                    }

                    exec.shutdown();
                    assertThat(exec.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
                    for (Future<Integer> f : futures) {
                        assertThat(f.get()).isEqualTo(1);
                    }
                });
    }
}
