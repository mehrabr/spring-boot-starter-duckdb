package io.github.mehrabr.duckdb.pool;

import io.github.mehrabr.duckdb.DuckDBProperties;
import org.duckdb.DuckDBConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 */
public class DuckDBConnectionPool implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(DuckDBConnectionPool.class);

    private final DuckDBConnection primary;
    private final BlockingQueue<DuckDBConnection> pool;
    private final long connectionTimeoutMs;
    private PrintWriter logWriter;

    public DuckDBConnectionPool(DuckDBProperties properties) {
        String path = properties.getPath();
        String url = (path == null || path.isBlank()) ? "jdbc:duckdb:" : "jdbc:duckdb:" + path;
        int maxSize = properties.getPool().getMaxSize();
        this.connectionTimeoutMs = properties.getPool().getConnectionTimeoutMs();
        this.pool = new LinkedBlockingQueue<>(maxSize);

        try {
            this.primary = (DuckDBConnection) DriverManager.getConnection(url);
            for (int i = 0; i < maxSize; i++) {
                pool.add((DuckDBConnection) primary.duplicate());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "DuckDB failed to open connection at '" + ((path == null || path.isBlank()) ? "in-memory" : path) + "'.", e);
        }

        log.info("DuckDB: read-write pool initialized with {} connection(s) (path={})",
                maxSize, (path == null || path.isBlank()) ? "in-memory" : path);
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            DuckDBConnection conn = pool.poll(connectionTimeoutMs, TimeUnit.MILLISECONDS);
            if (conn == null) {
                throw new SQLException(
                        "DuckDB connection pool timed out after " + connectionTimeoutMs + "ms. "
                                + "Consider increasing duckdb.pool.connection-timeout-ms or duckdb.pool.max-size.");
            }
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

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("DuckDBConnectionPool does not use java.util.logging.");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("DuckDBConnectionPool does not wrap " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
