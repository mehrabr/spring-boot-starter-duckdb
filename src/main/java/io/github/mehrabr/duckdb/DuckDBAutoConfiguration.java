package io.github.mehrabr.duckdb;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Registers a DuckDB {@link DataSource} and, when Actuator is present, a health indicator.
 * All beans are conditional on the absence of a user-defined bean of the same type, so any
 * piece of this configuration can be overridden without disabling the rest.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.duckdb.DuckDBDriver")
@EnableConfigurationProperties(DuckDBProperties.class)
public class DuckDBAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource duckdbDataSource(DuckDBProperties properties) {
        return DuckDBDataSourceFactory.create(properties);
    }
}
