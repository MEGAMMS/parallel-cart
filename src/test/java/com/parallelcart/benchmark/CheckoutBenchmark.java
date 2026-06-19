package com.parallelcart.benchmark;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark for Checkout flow.
 * Measures latency of the critical, transactional checkout path.
 *
 * In production benchmarks:
 *  - Spin up an embedded DB (H2/Testcontainers PostgreSQL)
 *  - Inject repositories and the CartServiceImpl
 *  - Seed test data and run checkout end-to-end.
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CheckoutBenchmark {

    @Param({"1", "5", "10"})
    private int concurrencyLevel;

    @Setup
    public void setup() {
        // Initialize test data, DB schema, etc.
    }

    @Benchmark
    public void benchmarkCheckout() {
        // Simulate checkout processing time
        // In a real scenario, invoke cartService.checkout(userId, idempotencyKey)
        try {
            TimeUnit.MILLISECONDS.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(CheckoutBenchmark.class.getSimpleName())
                .param("concurrencyLevel", "1", "5", "10")
                .build();
        new Runner(opt).run();
    }
}
