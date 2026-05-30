package io.github.mehrabr.duckdb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDBHealthIndicatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DuckDBAutoConfiguration.class));

    @Test
    void registeredWhenActuatorPresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(DuckDBHealthIndicator.class));
    }

    @Test
    void reportsUpWithVersion() {
        runner.run(ctx -> {
            DuckDBHealthIndicator indicator = ctx.getBean(DuckDBHealthIndicator.class);
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKey("version");
            assertThat(health.getDetails().get("version")).asString().isNotBlank();
        });
    }

    @Test
    void absentWhenActuatorMissing() {
        runner.withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DuckDBHealthIndicator.class));
    }

    @Test
    void backsOffWhenCustomIndicatorProvided() {
        runner.withUserConfiguration(CustomIndicatorConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(DuckDBHealthIndicator.class));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class CustomIndicatorConfig {

        @org.springframework.context.annotation.Bean
        DuckDBHealthIndicator customIndicator(javax.sql.DataSource ds) {
            return new DuckDBHealthIndicator(ds);
        }
    }
}
