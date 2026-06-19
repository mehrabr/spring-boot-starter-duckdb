package io.github.mehrabr.duckdb;

import io.github.mehrabr.duckdb.reader.DefaultDuckDbReader;
import io.github.mehrabr.duckdb.reader.DuckDbReader;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Registers a DuckDB {@link DataSource}, the {@link DuckDbReader} read-helper (when
 * spring-jdbc is present), and, when Actuator is present, a health indicator.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean} so any piece can be overridden
 * without disabling the rest.
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

    /**
     * Registers {@link DuckDbReader} when spring-jdbc is on the classpath.
     * Separated into a nested class so this configuration loads cleanly without
     * spring-jdbc; Spring Boot evaluates {@code @ConditionalOnClass} via ASM,
     * meaning the inner class body (which references JdbcTemplate) is never loaded
     * when the condition is false.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    static class ReaderConfiguration {

        @Bean
        @ConditionalOnMissingBean(DuckDbReader.class)
        public DefaultDuckDbReader duckdbReader(DataSource dataSource) {
            return new DefaultDuckDbReader(dataSource);
        }
    }

    /**
     * Nested configuration so that the reference to {@link DuckDBHealthIndicator}
     * (which extends a class from spring-boot-actuator) is only loaded when actuator
     * is actually on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class ActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public DuckDBHealthIndicator duckdbHealthIndicator(DataSource dataSource) {
            return new DuckDBHealthIndicator(dataSource);
        }
    }
}
