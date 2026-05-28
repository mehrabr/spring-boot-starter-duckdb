package io.github.mehrabr.duckdb;

import io.github.mehrabr.duckdb.pool.DuckDBConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Creates the appropriate {@link DataSource} for the configured DuckDB mode.
 *
 * <p>Three paths exist: read-write local (uses {@code DuckDBConnectionPool} with
 * {@code duplicate()}-based connections to avoid the JVM crash that results from
 * opening the same file twice for writing), read-only local (standard HikariCP),
 * and MotherDuck (HikariCP with an {@code md:} JDBC URL and token auth).
 */
public final class DuckDBDataSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(DuckDBDataSourceFactory.class);

    private DuckDBDataSourceFactory() {
    }

    public static DataSource create(DuckDBProperties properties) {
        if (properties.getMotherduck().isEnabled()) {
            log.info("DuckDB: using MotherDuck mode");
            return createMotherDuck(properties);
        }
        if (properties.isReadOnly()) {
            log.info("DuckDB: using read-only mode with HikariCP (path={})",
                    pathDescription(properties.getPath()));
            return createReadOnly(properties);
        }
        log.info("DuckDB: using read-write mode with DuckDBConnectionPool (path={})",
                pathDescription(properties.getPath()));
        return createReadWrite(properties);
    }

    private static DataSource createReadWrite(DuckDBProperties properties) {
        return new DuckDBConnectionPool(properties);
    }

    private static DataSource createReadOnly(DuckDBProperties properties) {
        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        String url = jdbcUrl(properties.getPath(), true);
        config.setJdbcUrl(url);
        config.setDriverClassName("org.duckdb.DuckDBDriver");
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(properties.getPool().getConnectionTimeoutMs());
        config.setIdleTimeout(properties.getPool().getIdleTimeoutMs());
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    private static DataSource createMotherDuck(DuckDBProperties properties) {
        DuckDBProperties.MotherDuck md = properties.getMotherduck();
        String token = resolveToken(md);
        String database = md.getDatabase() != null ? md.getDatabase() : "";
        String url = "jdbc:duckdb:md:" + database + "?motherduck_token=" + token;

        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(url);
        config.setDriverClassName("org.duckdb.DuckDBDriver");
        config.setConnectionTimeout(properties.getPool().getConnectionTimeoutMs());
        config.setIdleTimeout(properties.getPool().getIdleTimeoutMs());
        return new com.zaxxer.hikari.HikariDataSource(config);
    }

    private static String resolveToken(DuckDBProperties.MotherDuck md) {
        if (md.getToken() != null && !md.getToken().isBlank()) {
            return md.getToken();
        }
        String env = System.getenv("MOTHERDUCK_TOKEN");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "DuckDB MotherDuck token not found. Set duckdb.motherduck.token or MOTHERDUCK_TOKEN.");
    }

    private static String jdbcUrl(String path, boolean readOnly) {
        String base = (path == null || path.isBlank()) ? "jdbc:duckdb:" : "jdbc:duckdb:" + path;
        return readOnly ? base + "?access_mode=READ_ONLY" : base;
    }

    private static String pathDescription(String path) {
        return (path == null || path.isBlank()) ? "in-memory" : path;
    }
}
