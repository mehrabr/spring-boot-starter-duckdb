package io.github.mehrabr.duckdb;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@ConditionalOnClass(name = "org.duckdb.DuckDBDriver")
@EnableConfigurationProperties(DuckDBProperties.class)
public class DuckDBAutoConfiguration {
}
