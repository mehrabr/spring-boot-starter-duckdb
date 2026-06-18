package io.github.mehrabr.duckdb.reader;

import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Typed, injection-safe reads over external data sources (Parquet, CSV, JSON)
 * via DuckDB's {@code read_parquet} / {@code read_csv} / {@code read_json} functions.
 *
 * <p>File paths are always bound as JDBC parameters or strictly validated before
 * substitution — user-supplied paths cannot inject SQL (ADR-6).
 */
public interface DuckDbReader {

    /** Reads every row from {@code source}, mapping each row via {@code mapper}. */
    <T> List<T> read(Source source, RowMapper<T> mapper);

    /**
     * Starts a named-parameter query builder. Bind both data values and file paths
     * via {@link Query#param(String, Object)} — do not interpolate them into the SQL string.
     */
    Query sql(String sql);

    interface Query {

        /** Binds a named parameter. Use for both scalar values and file paths. */
        Query param(String name, Object value);

        <T> List<T> query(RowMapper<T> mapper);

        <T> Optional<T> queryOne(RowMapper<T> mapper);

        /** Execute the SQL and return the first column of the first row as a {@code long}. */
        long count();

        /**
         * Streams results, holding a pool connection for the entire lifetime of the returned
         * {@link Stream}.
         *
         * <p><strong>Always use try-with-resources.</strong> With the default pool size of 1
         * a single un-closed stream will deadlock the next connection acquire.
         */
        <T> Stream<T> stream(RowMapper<T> mapper);
    }
}
