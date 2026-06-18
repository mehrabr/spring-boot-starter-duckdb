package io.github.mehrabr.duckdb.reader;

/**
 * Typed options for {@link Source#csv(String, CsvOptions)}.
 *
 * <p>Only a validated allow-list of DuckDB CSV named arguments is exposed.
 * There is no free-form passthrough; all values are generated, not user-interpolated.
 */
public final class CsvOptions {

    private final Boolean header;
    private final Character delimiter;
    private final Boolean hivePartitioning;
    private final Boolean unionByName;

    private CsvOptions(Builder b) {
        this.header = b.header;
        this.delimiter = b.delimiter;
        this.hivePartitioning = b.hivePartitioning;
        this.unionByName = b.unionByName;
    }

    public static Builder builder() {
        return new Builder();
    }

    String toSql() {
        StringBuilder sb = new StringBuilder();
        if (header != null) sb.append(", header=").append(header);
        if (delimiter != null) sb.append(", delim='").append(delimiter).append("'");
        if (hivePartitioning != null) sb.append(", hive_partitioning=").append(hivePartitioning);
        if (unionByName != null) sb.append(", union_by_name=").append(unionByName);
        return sb.toString();
    }

    public static final class Builder {
        private Boolean header;
        private Character delimiter;
        private Boolean hivePartitioning;
        private Boolean unionByName;

        public Builder header(boolean v) { this.header = v; return this; }
        public Builder delimiter(char v) { this.delimiter = v; return this; }
        public Builder hivePartitioning(boolean v) { this.hivePartitioning = v; return this; }
        public Builder unionByName(boolean v) { this.unionByName = v; return this; }
        public CsvOptions build() { return new CsvOptions(this); }
    }
}
