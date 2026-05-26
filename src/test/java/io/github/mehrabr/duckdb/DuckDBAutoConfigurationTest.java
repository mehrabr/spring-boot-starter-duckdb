package io.github.mehrabr.duckdb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DuckDBAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DuckDBAutoConfiguration.class));

    @Test
    void contextLoadsWithDefaults() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
