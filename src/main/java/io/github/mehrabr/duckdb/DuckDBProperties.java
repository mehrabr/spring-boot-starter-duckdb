package io.github.mehrabr.duckdb;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Extensions to {@code LOAD} on every pooled connection (e.g. {@code httpfs}).
     * Loading on the root connection alone is insufficient — {@code LOAD} is per-connection state.
     */
    private List<String> extensions = new ArrayList<>();

    /**
     * Local directory containing pre-provisioned DuckDB extension files.
     * Set this for airgapped / offline environments where {@code INSTALL} cannot reach the network.
     */
    private String extensionDirectory;

    private S3 s3 = new S3();
    private Reader reader = new Reader();
    private MotherDuck motherduck = new MotherDuck();
    private Pool pool = new Pool();

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public List<String> getExtensions() { return extensions; }
    public void setExtensions(List<String> extensions) { this.extensions = extensions; }

    public String getExtensionDirectory() { return extensionDirectory; }
    public void setExtensionDirectory(String extensionDirectory) { this.extensionDirectory = extensionDirectory; }

    public S3 getS3() { return s3; }
    public void setS3(S3 s3) { this.s3 = s3; }

    public Reader getReader() { return reader; }
    public void setReader(Reader reader) { this.reader = reader; }

    public MotherDuck getMotherduck() { return motherduck; }
    public void setMotherduck(MotherDuck motherduck) { this.motherduck = motherduck; }

    public Pool getPool() { return pool; }
    public void setPool(Pool pool) { this.pool = pool; }

    // ── S3 ────────────────────────────────────────────────────────────────────

    /**
     * S3 / object-store credentials written as a DuckDB Secrets Manager temporary secret
     * on each pooled connection init (ADR-4). Secret values are never logged.
     */
    public static class S3 {

        private String region;
        private String endpoint = "s3.amazonaws.com";

        /** {@code vhost} (default, AWS) or {@code path} (MinIO / R2 / Ceph). */
        private String urlStyle = "vhost";

        /**
         * When {@code true}, uses {@code PROVIDER credential_chain} (AWS default chain)
         * instead of explicit key/secret. Ignore {@code accessKeyId} and {@code secretAccessKey}.
         */
        private boolean useCredentialChain = false;

        private String accessKeyId;
        private String secretAccessKey;

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getUrlStyle() { return urlStyle; }
        public void setUrlStyle(String urlStyle) { this.urlStyle = urlStyle; }

        public boolean isUseCredentialChain() { return useCredentialChain; }
        public void setUseCredentialChain(boolean useCredentialChain) { this.useCredentialChain = useCredentialChain; }

        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

        public String getSecretAccessKey() { return secretAccessKey; }
        public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }

        /** Returns {@code true} when enough config is present to create a Secrets Manager secret. */
        public boolean isConfigured() {
            return useCredentialChain
                    || (accessKeyId != null && !accessKeyId.isBlank()
                        && secretAccessKey != null && !secretAccessKey.isBlank());
        }
    }

    // ── Reader ────────────────────────────────────────────────────────────────

    public static class Reader {

        /** JDBC fetch-size hint for {@code DuckDbReader.sql(...).stream()}. Uses driver default when null. */
        private Integer defaultStreamFetchSize;

        public Integer getDefaultStreamFetchSize() { return defaultStreamFetchSize; }
        public void setDefaultStreamFetchSize(Integer v) { this.defaultStreamFetchSize = v; }
    }

    // ── MotherDuck ────────────────────────────────────────────────────────────

    public static class MotherDuck {

        private boolean enabled = false;

        /**
         * MotherDuck authentication token. If unset, falls back to the
         * MOTHERDUCK_TOKEN environment variable.
         */
        private String token;

        /** MotherDuck database name, e.g. "my_db". */
        private String database;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }

        public String getDatabase() { return database; }
        public void setDatabase(String database) { this.database = database; }
    }

    // ── Pool ──────────────────────────────────────────────────────────────────

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

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

        public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(long connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }

        public long getIdleTimeoutMs() { return idleTimeoutMs; }
        public void setIdleTimeoutMs(long idleTimeoutMs) { this.idleTimeoutMs = idleTimeoutMs; }
    }
}
