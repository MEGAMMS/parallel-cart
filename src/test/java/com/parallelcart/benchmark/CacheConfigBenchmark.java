package com.parallelcart.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark for CacheConfig default cache configuration overhead.
 *
 * This benchmark helps identify if cache serialization/deserialization
 * introduces measurable latency under various thread counts.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(4)
public class CacheConfigBenchmark {

    @Benchmark
    public void benchmarkCacheSerializationOverhead() {
        // Simulate a small serialization task similar to JdkSerializationRedisSerializer.
        // In production, replace with actual Redis cache put/get via Testcontainers Redis.
        String data = "test-cache-value";
        byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String roundTrip = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!data.equals(roundTrip)) {
            throw new IllegalStateException("Round-trip mismatch");
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(CacheConfigBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
