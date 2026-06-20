package com.parallelcart.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkMetricsService {

    private final MeterRegistry meterRegistry;

    public BenchmarkMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    public void increment(String name, Tags tags) {
        counter(name, tags).increment();
    }

    public void increment(String name, Tags tags, double amount) {
        counter(name, tags).increment(amount);
    }

    public void recordDistribution(String name, Tags tags, double value) {
        DistributionSummary.builder(name)
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(value);
    }

    public void recordDuration(String name, Tags tags, long durationNanos) {
        timer(name, tags).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordDuration(String name, Tags tags, Duration duration) {
        timer(name, tags).record(duration);
    }

    public <T> T time(String name, Tags tags, ThrowingSupplier<T> action) {
        long startNanos = System.nanoTime();
        try {
            T result = action.get();
            recordDuration(name, tags.and("status", "success"), System.nanoTime() - startNanos);
            return result;
        } catch (RuntimeException ex) {
            recordDuration(name, tags.and("status", "error"), System.nanoTime() - startNanos);
            increment(name + ".errors", tags.and("error", ex.getClass().getSimpleName()));
            throw ex;
        }
    }

    public void time(String name, Tags tags, ThrowingRunnable action) {
        time(name, tags, () -> {
            action.run();
            return null;
        });
    }

    private Counter counter(String name, Tags tags) {
        return Counter.builder(name)
                .tags(tags)
                .register(meterRegistry);
    }

    private Timer timer(String name, Tags tags) {
        return Timer.builder(name)
                .tags(tags)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(meterRegistry);
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get();
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run();
    }
}
