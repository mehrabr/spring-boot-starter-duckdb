package io.github.mehrabr.duckdb.pool;

import io.github.mehrabr.duckdb.DuckDBProperties;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A minimal connection pool for DuckDB in read-write mode.
 *
 * <p>DuckDB does not support multiple independent JDBC connections to the same file
 * when opened for writing. Attempting to call {@code DriverManager.getConnection()}
 * twice on the same path will crash the JVM, not throw an exception. The only safe
 * way to share a read-write file across threads is to open one root connection and
 * derive all further connections via {@link DuckDBConnection#duplicate()}.
 *
 * <p>This pool opens one root connection and pre-fills a {@link BlockingQueue} with
 * {@code maxSize} duplicated connections. Each {@link #getConnection()} call polls
 * the queue with a timeout; the returned {@link Connection} wrapper returns itself
 * to the queue on {@code close()}.
 *
 * <p>Extensions listed in {@code duckdb.extensions} are {@code LOAD}-ed on each
 * duplicated connection at pool-fill time. S3 credentials (when {@code duckdb.s3}
 * is configured) are written as a {@code TEMPORARY SECRET} per connection so that
 * secret values never appear in logs or persist to disk (ADR-4).
 */
public class DuckDBConnectionPool implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(DuckDBConnectionPool.class);

    private final DuckDBConnection primary;
    private final BlockingQueue<DuckDBConnection> pool;
    private final long connectionTimeoutMs;
    private final String mode;
    private final List<String> extensions;
    private final DuckDBProperties.S3 s3Config;
    private PrintWriter logWriter;

    // Metric callbacks — wired by DuckDBMetricsAutoConfiguration when Micrometer is present.
    private volatile Consumer<String> connectionAcquiredMetrics = m -> {};
    private volatile BiConsumer<String, Long> poolWaitMsMetrics = (m, ms) -> {};

    public DuckDBConnectionPool(DuckDBProperties properties) {
        String path = properties.getPath();
        String url = (path == null || path.isBlank()) ? "jdbc:duckdb:" : "jdbc:duckdb:" + path;
        int maxSize = properties.getPool().getMaxSize();
        this.connectionTimeoutMs = properties.getPool().getConnectionTimeoutMs();
        this.mode = (path == null || path.isBlank()) ? "memory" : "file_rw";
        this.extensions = List.copyOf(properties.getExtensions());
        this.s3Config = properties.getS3();
        this.pool = new LinkedBlockingQueue<>(maxSize);

        try {
            this.primary = (DuckDBConnection) DriverManager.getConnection(url);
            for (int i = 0; i < maxSize; i++) {
                DuckDBConnection conn = (DuckDBConnection) primary.duplicate();
                initConnection(conn);
                pool.add(conn);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DuckDB failed to open connection at '"
                            + ((path == null || path.isBlank()) ? "in-memory" : path) + "'.", e);
        }

        log.info("DuckDB: read-write pool initialized with {} connection(s) (path={})",
                maxSize, (path == null || path.isBlank()) ? "in-memory" : path);
    }

    /** Called by {@code DuckDBMetricsAutoConfiguration} when Micrometer is present. */
    public void configureMetrics(Consumer<String> connectionAcquired,
                                 BiConsumer<String, Long> poolWaitMs) {
        this.connectionAcquiredMetrics = connectionAcquired;
        this.poolWaitMsMetrics = poolWaitMs;
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            long start = System.currentTimeMillis();
            DuckDBConnection conn = pool.poll(connectionTimeoutMs, TimeUnit.MILLISECONDS);
            poolWaitMsMetrics.accept(mode, System.currentTimeMillis() - start);
            if (conn == null) {
                throw new SQLException(
                        "DuckDB connection pool timed out after " + connectionTimeoutMs + "ms. "
                                + "Consider increasing duckdb.pool.connection-timeout-ms or duckdb.pool.max-size.");
            }
            connectionAcquiredMetrics.accept(mode);
            return new PooledDuckDBConnection(conn, pool);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a DuckDB connection.", e);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    /** Closes all pooled connections and the primary connection. */
    public void close() throws SQLException {
        List<DuckDBConnection> drained = new ArrayList<>();
        pool.drainTo(drained);
        for (DuckDBConnection conn : drained) {
            conn.close();
        }
        primary.close();
    }

    // ── Per-connection initialization ─────────────────────────────────────────

    private void initConnection(DuckDBConnection conn) throws SQLException {
        loadExtensions(conn);
        if (s3Config != null && s3Config.isConfigured()) {
            createS3Secret(conn, s3Config);
        }
    }

    private void loadExtensions(DuckDBConnection conn) throws SQLException {
        for (String ext : extensions) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("LOAD " + ext);
                log.debug("DuckDB: loaded extension '{}' on pooled connection", ext);
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "Failed to load DuckDB extension '" + ext + "'. "
                                + "Run INSTALL " + ext + " first, or set duckdb.extension-directory "
                                + "for pre-provisioned (airgapped) installs.", e);
            }
        }
    }

    private static void createS3Secret(DuckDBConnection conn, DuckDBProperties.S3 s3) throws SQLException {
        StringBuilder sql = new StringBuilder("CREATE OR REPLACE TEMPORARY SECRET s3_default (TYPE s3");
        if (s3.isUseCredentialChain()) {
            sql.append(", PROVIDER credential_chain");
        } else {
            if (s3.getRegion() != null && !s3.getRegion().isBlank()) {
                sql.append(", REGION '").append(s3.getRegion()).append("'");
            }
            sql.append(", ENDPOINT '").append(s3.getEndpoint()).append("'");
            sql.append(", URL_STYLE '").append(s3.getUrlStyle()).append("'");
            sql.append(", KEY_ID '").append(s3.getAccessKeyId()).append("'");
            sql.append(", SECRET '").append(s3.getSecretAccessKey()).append("'");
        }
        sql.append(")");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        }
        // Intentionally no logging of the SQL — it contains the secret value.
        log.debug("DuckDB: S3 temporary secret created on pooled connection");
    }

    // ── DataSource boilerplate ─────────────────────────────────────────────────

    @Override public PrintWriter getLogWriter() { return logWriter; }
    @Override public void setLogWriter(PrintWriter out) { this.logWriter = out; }
    @Override public void setLoginTimeout(int seconds) {}
    @Override public int getLoginTimeout() { return 0; }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("DuckDBConnectionPool does not use java.util.logging.");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("DuckDBConnectionPool does not wrap " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
