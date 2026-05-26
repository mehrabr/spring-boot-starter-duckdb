package io.github.mehrabr.duckdb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for DuckDB and MotherDuck connections.
 * All properties are optional; defaults produce an in-memory DuckDB instance.
 */
@ConfigurationProperties(prefix = "duckdb")
public class DuckDBProperties {

    /** Path to the DuckDB database file. Empty or null means in-memory. */
    private String path = "";

    /** Open the database in read-only mode. Safe for standard HikariCP pooling. */
    private boolean readOnly = false;

    private MotherDuck motherduck = new MotherDuck();

    private Pool pool = new Pool();

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public MotherDuck getMotherduck() {
        return motherduck;
    }

    public void setMotherduck(MotherDuck motherduck) {
        this.motherduck = motherduck;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    public static class MotherDuck {

        private boolean enabled = false;

        /**
         * MotherDuck authentication token. If unset, falls back to the
         * MOTHERDUCK_TOKEN environment variable.
         */
        private String token;

        /** MotherDuck database name, e.g. "my_db". */
        private String database;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }
    }

    public static class Pool {

        /**
         * Maximum number of pooled connections. Defaults to 1 for read-write mode
         * because DuckDB serializes writes anyway and extra connections waste memory.
         * Ignored in read-only and MotherDuck modes (HikariCP manages the pool).
         */
        private int maxSize = 1;

        /** How long to wait for a connection before throwing, in milliseconds. */
        private long connectionTimeoutMs = 30_000;

        /** How long an idle connection may remain in the pool before being closed, in milliseconds. */
        private long idleTimeoutMs = 600_000;

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }

        public long getIdleTimeoutMs() {
            return idleTimeoutMs;
        }

        public void setIdleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
        }
    }
}
