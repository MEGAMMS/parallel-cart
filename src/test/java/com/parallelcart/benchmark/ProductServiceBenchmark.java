package com.parallelcart.benchmark;

import com.parallelcart.api.dto.ProductResponse;
import com.parallelcart.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH Benchmark for ProductService read operations.
 *
 * Run with: mvn test-compile exec:java -Dexec.mainClass=com.parallelcart.benchmark.ProductServiceBenchmark
 * Or: java -cp target/test-classes:target/classes com.parallelcart.benchmark.ProductServiceBenchmark
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ProductServiceBenchmark {

    private ProductService productService;

    @Setup
    public void setup() {
        // In a real benchmark, you'd wire up a minimal Spring context
        // or use a mock/stub to isolate product reads.
        // For demonstration, this shows the benchmark structure.
        // Use @Benchmark with a manually constructed stub or Spring Boot test context.
    }

    /**
     * Benchmark: Measure raw throughput of listing products.
     * In practice, wire a stub or lightweight in-memory ProductServiceImpl.
     */
    @Benchmark
    public void benchmarkListProducts(Blackhole blackhole) {
        // Placeholder: replace with actual service call in real setup
        List<ProductResponse> products = List.of(
                new ProductResponse(1L, "SKU-001", "Product A", "Desc A", BigDecimal.valueOf(19.99), true),
                new ProductResponse(2L, "SKU-002", "Product B", "Desc B", BigDecimal.valueOf(29.99), true)
        );
        blackhole.consume(products);
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(ProductServiceBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
