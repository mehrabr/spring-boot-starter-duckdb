package io.github.mehrabr.duckdb;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Reports DuckDB availability and version via Spring Boot Actuator.
 * Only registered when {@code spring-boot-starter-actuator} is on the classpath.
 */
public class DuckDBHealthIndicator extends AbstractHealthIndicator {

    private final DataSource dataSource;

    public DuckDBHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version() AS version")) {
            if (rs.next()) {
                builder.up().withDetail("version", rs.getString("version"));
            } else {
                builder.up();
            }
        }
    }
}
