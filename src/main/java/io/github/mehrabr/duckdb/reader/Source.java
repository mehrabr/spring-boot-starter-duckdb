package io.github.mehrabr.duckdb.reader;

/**
 * Describes an external data source (Parquet, CSV, or JSON) for use with {@link DuckDbReader}.
 *
 * <p>The path is always kept separate from the SQL fragment it generates — it is
 * bound as a JDBC parameter, never interpolated directly into SQL.
 */
public abstract class Source {

    private final String path;

    private Source(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Source path must not be blank");
        }
        this.path = path;
    }

    public static Source parquet(String path) {
        return new Parquet(path);
    }

    public static Source csv(String path) {
        return new Csv(path, null);
    }

    public static Source csv(String path, CsvOptions options) {
        return new Csv(path, options);
    }

    public static Source json(String path) {
        return new Json(path);
    }

    public String getPath() {
        return path;
    }

    public abstract String functionName();

    public abstract String sourceTag();

    String optionsSql() {
        return "";
    }

    /**
     * Returns a DuckDB table-function SQL fragment with {@code ?} as the path placeholder,
     * e.g. {@code read_parquet(?, hive_partitioning=true)}.
     */
    public String toParameterizedSql() {
        return functionName() + "(?" + optionsSql() + ")";
    }

    /** Returns {@code s3}, {@code http}, or {@code local} based on the path prefix. */
    public String locationTag() {
        String p = path.toLowerCase();
        if (p.startsWith("s3://") || p.startsWith("s3a://") || p.startsWith("s3n://")) return "s3";
        if (p.startsWith("http://") || p.startsWith("https://")) return "http";
        return "local";
    }

    static final class Parquet extends Source {
        Parquet(String path) { super(path); }

        @Override public String functionName() { return "read_parquet"; }
        @Override public String sourceTag() { return "parquet"; }
    }

    static final class Csv extends Source {
        private final CsvOptions options;

        Csv(String path, CsvOptions options) {
            super(path);
            this.options = options;
        }

        @Override public String functionName() { return "read_csv"; }
        @Override public String sourceTag() { return "csv"; }
        @Override String optionsSql() { return options != null ? options.toSql() : ""; }
    }

    static final class Json extends Source {
        Json(String path) { super(path); }

        @Override public String functionName() { return "read_json"; }
        @Override public String sourceTag() { return "json"; }
    }
}
