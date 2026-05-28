package io.github.mehrabr.duckdb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDBAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DuckDBAutoConfiguration.class));

    @Test
    void contextLoadsWithDefaults() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(DataSource.class);
        });
    }

    @Test
    void backsOffWhenDataSourcePresent() {
        runner.withUserConfiguration(CustomDataSourceConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(DataSource.class);
                    assertThat(ctx.getBean(DataSource.class))
                            .isInstanceOf(CustomDataSourceConfig.StubDataSource.class);
                });
    }

    @Test
    void inMemoryDataSourceIsUsableForQueries() {
        runner.run(ctx -> {
            DataSource ds = ctx.getBean(DataSource.class);
            try (var conn = ds.getConnection();
                 var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT 42 AS answer")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("answer")).isEqualTo(42);
            }
        });
    }

    // Minimal stand-in DataSource so the back-off condition can be tested.
    static class CustomDataSourceConfig {

        @org.springframework.context.annotation.Bean
        StubDataSource dataSource() {
            return new StubDataSource();
        }

        static class StubDataSource implements DataSource {
            @Override public java.sql.Connection getConnection() { return null; }
            @Override public java.sql.Connection getConnection(String u, String p) { return null; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter pw) {}
            @Override public void setLoginTimeout(int s) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> i) { return null; }
            @Override public boolean isWrapperFor(Class<?> i) { return false; }
        }
    }
}
