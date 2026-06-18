package io.github.mehrabr.duckdb.reader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Default {@link DuckDbReader} implementation built on {@link JdbcTemplate}.
 *
 * <p>Paths are bound as JDBC parameters via {@code read_parquet(?)} etc., so
 * a malicious path value cannot inject SQL (ADR-6). Extension loading and S3
 * credential injection happen at the connection level in {@code DuckDBConnectionPool}.
 */
public class DefaultDuckDbReader implements DuckDbReader {

    private static final Logger log = LoggerFactory.getLogger(DefaultDuckDbReader.class);

    private static final java.lang.ref.Cleaner CLEANER = java.lang.ref.Cleaner.create();

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    // Metric callbacks — wired by DuckDBMetricsAutoConfiguration when Micrometer is present.
    // Default no-ops avoid null checks throughout.
    private volatile Consumer<Source> queryMetrics = s -> {};
    private volatile Runnable streamOpenMetrics = () -> {};
    private volatile Runnable streamLeakedMetrics = () -> {};

    public DefaultDuckDbReader(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.namedJdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** Called by {@code DuckDBMetricsAutoConfiguration} when Micrometer is present. */
    public void configureMetrics(Consumer<Source> queryMetrics,
                                 Runnable streamOpenMetrics,
                                 Runnable streamLeakedMetrics) {
        this.queryMetrics = queryMetrics;
        this.streamOpenMetrics = streamOpenMetrics;
        this.streamLeakedMetrics = streamLeakedMetrics;
    }

    @Override
    public <T> List<T> read(Source source, RowMapper<T> mapper) {
        queryMetrics.accept(source);
        String sql = "SELECT * FROM " + source.toParameterizedSql();
        return jdbc.query(sql, mapper, source.getPath());
    }

    @Override
    public Query sql(String sql) {
        return new QueryImpl(sql);
    }

    private class QueryImpl implements Query {

        private final String sql;
        private final Map<String, Object> params = new LinkedHashMap<>();

        QueryImpl(String sql) {
            this.sql = sql;
        }

        @Override
        public Query param(String name, Object value) {
            params.put(name, value);
            return this;
        }

        @Override
        public <T> List<T> query(RowMapper<T> mapper) {
            return namedJdbc.query(sql, params, mapper);
        }

        @Override
        public <T> Optional<T> queryOne(RowMapper<T> mapper) {
            List<T> results = namedJdbc.query(sql, params, mapper);
            if (results.isEmpty()) return Optional.empty();
            if (results.size() > 1) throw new IncorrectResultSizeDataAccessException(1, results.size());
            return Optional.of(results.get(0));
        }

        @Override
        public long count() {
            Long result = namedJdbc.queryForObject(sql, params, Long.class);
            return result != null ? result : 0L;
        }

        @Override
        public <T> Stream<T> stream(RowMapper<T> mapper) {
            return streamQuery(sql, params, mapper);
        }
    }

    private <T> Stream<T> streamQuery(String namedSql, Map<String, Object> params, RowMapper<T> mapper) {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = dataSource.getConnection();

            String jdbcSql = namedToJdbc(namedSql);
            stmt = conn.prepareStatement(jdbcSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            stmt.setFetchSize(1000);
            bindInOrder(stmt, namedSql, params);
            rs = stmt.executeQuery();

            StreamState state = new StreamState(rs, stmt, conn, streamLeakedMetrics);
            CLEANER.register(state, state);

            streamOpenMetrics.run();

            Stream<T> stream = StreamSupport.stream(new ResultSetSpliterator<>(rs, mapper), false);
            return stream.onClose(state::closeOnce);

        } catch (SQLException e) {
            closeQuietly(rs, stmt, conn);
            throw new UncategorizedSQLException("DuckDbReader stream failed", namedSql, e);
        }
    }

    /** Replaces {@code :name} with {@code ?} in declaration order. */
    static String namedToJdbc(String sql) {
        return sql.replaceAll(":(\\w+)", "?");
    }

    /** Binds positional parameters in the order the named placeholders appear in the SQL. */
    static void bindInOrder(PreparedStatement ps, String namedSql, Map<String, Object> params)
            throws SQLException {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile(":(\\w+)").matcher(namedSql);
        int idx = 1;
        while (m.find()) {
            ps.setObject(idx++, params.get(m.group(1)));
        }
    }

    static void closeQuietly(ResultSet rs, Statement stmt, Connection conn) {
        try { if (rs != null) rs.close(); } catch (Exception ignored) {}
        try { if (stmt != null) stmt.close(); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
    }

    /**
     * Holds JDBC resources for an open stream. Acts as both the onClose handler and the
     * Cleaner cleanup action so leaked (un-closed) streams are detected and their
     * connections recovered.
     */
    private static final class StreamState implements Runnable {

        private final ResultSet rs;
        private final Statement stmt;
        private final Connection conn;
        private final Runnable leakedMetrics;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        StreamState(ResultSet rs, Statement stmt, Connection conn, Runnable leakedMetrics) {
            this.rs = rs;
            this.stmt = stmt;
            this.conn = conn;
            this.leakedMetrics = leakedMetrics;
        }

        /** Called by the stream's {@code onClose} handler. */
        void closeOnce() {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(rs, stmt, conn);
            }
        }

        /** Called by the Cleaner when the stream is GC-d without close — indicates a leak. */
        @Override
        public void run() {
            if (!closed.get()) {
                leakedMetrics.run();
                closeOnce();
            }
        }
    }

    private static final class ResultSetSpliterator<T> implements java.util.Spliterator<T> {

        private final ResultSet rs;
        private final RowMapper<T> mapper;
        private int rowNum;

        ResultSetSpliterator(ResultSet rs, RowMapper<T> mapper) {
            this.rs = rs;
            this.mapper = mapper;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            try {
                if (!rs.next()) return false;
                action.accept(mapper.mapRow(rs, rowNum++));
                return true;
            } catch (SQLException e) {
                throw new UncategorizedSQLException("row mapping failed", null, e);
            }
        }

        @Override public java.util.Spliterator<T> trySplit() { return null; }
        @Override public long estimateSize() { return Long.MAX_VALUE; }
        @Override public int characteristics() { return ORDERED | NONNULL; }
    }
}
