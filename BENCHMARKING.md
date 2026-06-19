# Benchmarking & Bottleneck Analysis

This document explains how to benchmark the `parallel-cart` application and analyze performance bottlenecks.

## Table of Contents
1. [Benchmarking with JMH](#1-benchmarking-with-jmh-java-microbenchmark-harness)
2. [Runtime Metrics via Actuator & Micrometer](#2-runtime-metrics-via-actuator--micrometer)
3. [Profiling Tools](#3-profiling-tools)
4. [Interpreting Results](#4-interpreting-results)
5. [Common Bottlenecks in this Codebase](#5-common-bottlenecks-in-this-codebase)

---

## 1. Benchmarking with JMH (Java Microbenchmark Harness)

We use **JMH** to measure isolated, reproducible performance of individual components.

### 1.1 Where to Find Benchmarks

```
src/test/java/com/parallelcart/benchmark/
├── ProductServiceBenchmark.java       -- Measures product read latency
├── CacheConfigBenchmark.java          -- Measures Redis serialization overhead
├── CheckoutBenchmark.java             -- Measures end-to-end checkout latency
└── InventoryRetryBenchmark.java       -- Measures optimistic lock retry cost
```

### 1.2 How to Run

**Option A: From Maven (recommended)**
```bash
mvn test-compile exec:java \
  -Dexec.mainClass="com.parallelcart.benchmark.ProductServiceBenchmark"
```

**Option B: From IDE**
Run the `main(String[] args)` method in any `*Benchmark.java` class.

**Option C: Full suite**
```bash
mvn test-compile
java -cp "target/test-classes:target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q)" \
  com.parallelcart.benchmark.ProductServiceBenchmark
```

### 1.3 What Each Benchmark Measures

| Benchmark | Target Component | Key Metrics |
|-----------|------------------|-------------|
| `ProductServiceBenchmark` | `ProductServiceImpl` | Read latency with/without cache |
| `CacheConfigBenchmark` | `RedisCacheConfiguration` | Serialization/deserialization overhead |
| `CheckoutBenchmark` | `CartServiceImpl.checkout()` | End-to-end transactional latency |
| `InventoryRetryBenchmark` | `reserveInventoryWithRetry()` | Optimistic lock contention cost |

---

## 2. Runtime Metrics via Actuator & Micrometer

### 2.1 Enabled Endpoints (as configured in `application.yml`)

| Endpoint | URL | Purpose |
|----------|-----|---------|
| Health | `/actuator/health` | Readiness / Liveness probes |
| Metrics | `/actuator/metrics` | JVM, HTTP, system, custom metrics |
| Prometheus | `/actuator/prometheus` | Scrapable time-series data |

### 2.2 Custom Metrics from PerformanceTimingAspect

The `PerformanceTimingAspect` already logs:
- `product_read` -- Product listing / get by ID
- `checkout` -- Checkout endpoint
- `inventory_update` -- Inventory reservation (saveAndFlush)

**Example log output:**
```
perf_timing operation=checkout method=CartServiceImpl.checkout(..) status=success duration_ms=145
```

**Parse and aggregate for bottleneck detection:**
```bash
# Extract average checkout duration
grep "operation=checkout" application.log | \
  awk -F'duration_ms=' '{sum+=$2; count++} END {print "Avg:", sum/count "ms", "Count:", count}'
```

### 2.3 Key Micrometer Metrics to Watch

```bash
# JVM Memory
curl http://localhost:8080/actuator/metrics/jvm.memory.used

# HTTP Server Request Latency (percentiles)
curl http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/carts/\*/checkout

# Database Connection Pool (if HikariCP is used)
curl http://localhost:8080/actuator/metrics/jdbc.connections.active

# Cache Miss Rate (Spring Cache + Redis)
curl http://localhost:8080/actuator/metrics/cache.misses?tag=name:products
```

---

## 3. Profiling Tools

### 3.1 Java Flight Recorder (JFR) -- Built-in & Free

**Record during a benchmark or load test:**
```bash
# Attach to running Spring Boot process
jcmd <pid> JFR.start duration=60s filename=benchmark.jfr

# Or start with JFR enabled
java -XX:+UnlockDiagnosticVMOptions \
     -XX:+FlightRecorder \
     -XX:StartFlightRecording=duration=60s,filename=benchmark.jfr \
     -jar target/parallel-cart-*.jar
```

**Analyze in JDK Mission Control (JMC):**
- Look for **hot methods** (CPU samples)
- Monitor **allocation rate** (GC pressure)
- Check **lock contention** (critical for `reserveInventoryWithRetry`)

### 3.2 async-profiler (Linux / WSL / macOS)

Lightweight, low-overhead profiler. Ideal for production-like environments.

```bash
# Profile CPU
cd async-profiler
./profiler.sh -d 60 -f profile.html <pid>

# Profile allocations (heap pressure)
./profiler.sh -e alloc -d 60 -f alloc.html <pid>

# Profile lock contention (key for inventory locking)
./profiler.sh -e lock -d 60 -f lock.html <pid>
```

### 3.3 VisualVM (Built-in, GUI)

```bash
jvisualvm
```
- Attach to the running application
- Monitor CPU, memory, threads, and GC in real time

---

## 4. Interpreting Results

### 4.1 Checklist: Is it a Bottleneck?

| Symptom | Likely Cause | How to Confirm |
|---------|-------------|----------------|
| High `duration_ms` on `checkout` | DB lock contention, slow queries | JFR -> Database I/O samples |
| High CPU but low throughput | Inefficient algorithms, serialization | async-profiler CPU flame graph |
| High GC pause times | Object allocation in hot path | JFR GC events, VisualVM heap |
| Redis cache misses > 20% | Cache key space issues, TTL too short | Micrometer `cache.misses` / `cache.hits` |
| Thread pool exhaustion | `CallerRunsPolicy` blocking | Micrometer executor.queue.remaining |

### 4.2 Actionable Thresholds

| Metric | Warning | Critical |
|--------|---------|----------|
| Checkout p99 latency | > 500ms | > 2s |
| Cache hit rate | < 85% | < 70% |
| DB connection wait time | > 50ms | > 200ms |
| CPU utilization | > 70% | > 90% |
| GC pause (G1) | > 100ms | > 500ms |

---

## 5. Common Bottlenecks in this Codebase

### 5.1 Inventory Locking (`CartServiceImpl.reserveInventoryWithRetry`)

- **What it does:** Optimistic locking with 3 retries, falls back to pessimistic lock.
- **Bottleneck risk:** Under high concurrency, optimistic retries fail frequently, causing retry storms.
- **Benchmark:** `InventoryRetryBenchmark`
- **Profile:** JFR lock samples, async-profiler `-e lock`

### 5.2 Checkout Transaction (`CartServiceImpl.checkout`)

- **What it does:** Large transaction spanning inventory, order, payment, outbox, and cache invalidation.
- **Bottleneck risk:** Transaction duration increases with DB round trips and cache operations.
- **Benchmark:** `CheckoutBenchmark`
- **Profile:** JFR Database I/O, JPA query execution times

### 5.3 Redis Cache Serialization (`CacheConfig`)

- **What it does:** `JdkSerializationRedisSerializer` serializes values to Redis.
- **Bottleneck risk:** Java serialization is slower and more memory-intensive than alternatives (Kryo, JSON).
- **Benchmark:** `CacheConfigBenchmark`
- **Profile:** Heap dump / allocation profiler

### 5.4 Thread Pools (`ResourceManagementConfig`)

- **What it does:** Limits concurrent async tasks and scheduled jobs.
- **Bottleneck risk:** Under load, `CallerRunsPolicy` can backpressure into request threads.
- **Monitor:** `jvm.threads.live`, executor queue metrics

---

## Next Steps

1. **Run the JMH benchmarks** to establish baseline numbers.
2. **Start the application** and hit it with a load test (JMeter/Gatling).
3. **Record JFR** during the load test.
4. **Check Actuator metrics** `/actuator/metrics` for HTTP latency and cache hit rates.
5. **Analyze the JFR** in JDK Mission Control to find hot methods and lock contention.
6. **Iterate:** Fix the biggest bottleneck first, re-run benchmarks, compare.
