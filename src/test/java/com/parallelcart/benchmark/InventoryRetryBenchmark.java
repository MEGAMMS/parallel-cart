package com.parallelcart.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark for Inventory Optimistic Locking with Retry.
 *
 * Simulates the contention pattern found in CartServiceImpl.reserveInventoryWithRetry
 * to quantify the cost of retry loops and pessimistic fallback under contention.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class InventoryRetryBenchmark {

    private volatile int inventory = 1000;

    @Setup
    public void setup() {
        inventory = 1000;
    }

    @Benchmark
    public void benchmarkOptimisticLockRetry() {
        int retries = 0;
        while (true) {
            int current = inventory;
            if (current <= 0) {
                break;
            }
            // Simulate an optimistic concurrency check (CAS-style)
            if (inventory == current) {
                inventory = current - 1;
                break;
            }
            retries++;
            if (retries > 3) {
                break;
            }
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(InventoryRetryBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
