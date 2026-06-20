package io.github.mehrabr.duckdb;

import io.github.mehrabr.duckdb.pool.DuckDBConnectionPool;
import io.github.mehrabr.duckdb.reader.DefaultDuckDbReader;
import io.github.mehrabr.duckdb.reader.DuckDbReader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

/**
 * Wires Micrometer metrics into {@link DuckDBConnectionPool} and {@link DefaultDuckDbReader}
 * when {@code micrometer-core} is on the classpath.
 *
 * <p>This class is a separate {@code @AutoConfiguration} (not a nested class inside
 * {@link DuckDBAutoConfiguration}) so that all references to {@link MeterRegistry} and
 * other Micrometer types are confined here. Spring Boot's ASM-based condition evaluation
 * means this class is never loaded when {@code micrometer-core} is absent.
 *
 * <p>Metrics emitted (Section 7 of the design spec):
 * <ul>
 *   <li>{@code duckdb.connection.acquired} — counter, tag {@code mode}</li>
 *   <li>{@code duckdb.pool.wait} — timer, tag {@code mode}</li>
 *   <li>{@code duckdb.reader.query} — counter, tags {@code source}, {@code location}</li>
 *   <li>{@code duckdb.reader.stream.open} — counter</li>
 *   <li>{@code duckdb.reader.stream.leaked} — counter</li>
 * </ul>
 */
@AutoConfiguration(after = DuckDBAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
public class DuckDBMetricsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DuckDBMetricsAutoConfiguration.class);

    @Bean
    public DuckDBMetricsConfigurer duckdbMetricsConfigurer(
            ObjectProvider<DataSource> dataSourceProvider,
            ObjectProvider<DuckDbReader> readerProvider,
            MeterRegistry meterRegistry) {

        dataSourceProvider.ifAvailable(ds -> {
            if (ds instanceof DuckDBConnectionPool pool) {
                pool.configureMetrics(
                        mode -> Counter.builder("duckdb.connection.acquired")
                                .tag("mode", mode)
                                .register(meterRegistry)
                                .increment(),
                        (mode, ms) -> Timer.builder("duckdb.pool.wait")
                                .tag("mode", mode)
                                .register(meterRegistry)
                                .record(ms, TimeUnit.MILLISECONDS));
                log.debug("DuckDB: pool metrics wired");
            }
        });

        readerProvider.ifAvailable(reader -> {
            if (reader instanceof DefaultDuckDbReader dr) {
                dr.configureMetrics(
                        source -> Counter.builder("duckdb.reader.query")
                                .tag("source", source.sourceTag())
                                .tag("location", source.locationTag())
                                .register(meterRegistry)
                                .increment(),
                        () -> Counter.builder("duckdb.reader.stream.open")
                                .register(meterRegistry)
                                .increment(),
                        () -> Counter.builder("duckdb.reader.stream.leaked")
                                .register(meterRegistry)
                                .increment());
                log.debug("DuckDB: reader metrics wired");
            }
        });

        return new DuckDBMetricsConfigurer();
    }

    /** Marker bean; its sole purpose is to trigger the configurer method during context refresh. */
    public static final class DuckDBMetricsConfigurer {}
}
