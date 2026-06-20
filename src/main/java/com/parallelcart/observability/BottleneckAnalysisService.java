package com.parallelcart.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class BottleneckAnalysisService {

    private final MeterRegistry meterRegistry;

    public BottleneckAnalysisService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Map<String, Object> analyze() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("bottleneckRanking", bottleneckRanking());
        report.put("checkoutPhaseBreakdown", checkoutPhaseBreakdown());
        report.put("latency", latencySummaries());
        report.put("locks", lockSummary());
        report.put("cache", cacheSummary());
        report.put("database", databaseSummary());
        report.put("contentionHeatmap", productContentionHeatmap());
        report.put("resources", resourceSummary());
        return report;
    }

    private List<Map<String, Object>> bottleneckRanking() {
        List<Impact> impacts = List.of(
                new Impact("Inventory Lock Contention",
                        totalTimerMs("parallelcart.lock.wait.duration", "lock_type", "inventory")
                                + totalTimerMs("parallelcart.inventory.optimistic.retry.sleep.duration")
                                + totalTimerMs("parallelcart.inventory.pessimistic.lock.wait.duration")),
                new Impact("Database Query Execution", totalTimerMs("parallelcart.database.query.duration")),
                new Impact("Database Connection Wait", totalTimerMs("parallelcart.datasource.connection.acquire.duration")),
                new Impact("Cache Miss / Backing Load", totalTimerMs("parallelcart.cache.backing_load.duration")),
                new Impact("Outbox Write / Serialization", totalTimerMs("parallelcart.outbox.write.duration")),
                new Impact("Kafka / Outbox Publish", totalTimerMs("parallelcart.kafka.publish.ack.duration")
                        + totalTimerMs("parallelcart.outbox.publish.duration")),
                new Impact("Cache Invalidation", totalTimerMs("parallelcart.cache.invalidation.duration")),
                new Impact("Thread Pools / Backpressure", totalTimerMs("parallelcart.executor.queue.wait.duration")
                        + totalTimerMs("parallelcart.checkout.saturation.wait.duration"))
        );
        double total = impacts.stream().mapToDouble(Impact::impactMs).sum();
        return impacts.stream()
                .filter(impact -> impact.impactMs() > 0)
                .sorted(Comparator.comparingDouble(Impact::impactMs).reversed())
                .map(impact -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", impact.name());
                    item.put("impactMs", round(impact.impactMs()));
                    item.put("impactPct", total == 0 ? 0 : round(impact.impactMs() * 100.0 / total));
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> checkoutPhaseBreakdown() {
        List<Timer> timers = timersByName("parallelcart.checkout.phase.duration");
        double totalMs = timers.stream().mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum();
        return timers.stream()
                .sorted(Comparator.comparing(timer -> Objects.toString(timer.getId().getTag("phase"), "")))
                .map(timer -> {
                    double phaseMs = timer.totalTime(TimeUnit.MILLISECONDS);
                    Map<String, Object> item = timerStats(timer);
                    item.put("phase", timer.getId().getTag("phase"));
                    item.put("totalMs", round(phaseMs));
                    item.put("contributionPct", totalMs == 0 ? 0 : round(phaseMs * 100.0 / totalMs));
                    return item;
                })
                .toList();
    }

    private Map<String, Object> latencySummaries() {
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("checkoutTotal", timerStats("parallelcart.checkout.total.duration"));
        latency.put("databaseQueries", groupedTimerStats("parallelcart.database.query.duration", "query"));
        latency.put("lockWait", groupedTimerStats("parallelcart.lock.wait.duration", "lock_type"));
        latency.put("lockHold", groupedTimerStats("parallelcart.lock.hold.duration", "lock_type"));
        latency.put("cacheOperations", groupedTimerStats("parallelcart.cache.operation.duration", "cache"));
        latency.put("kafkaPublishAck", timerStats("parallelcart.kafka.publish.ack.duration"));
        return latency;
    }

    private Map<String, Object> lockSummary() {
        Map<String, Object> locks = new LinkedHashMap<>();
        locks.put("acquireAttempts", counterTotal("parallelcart.lock.acquire.total"));
        locks.put("acquireFailures", counterTotal("parallelcart.lock.acquire.failed.total"));
        locks.put("optimisticRetries", counterTotal("parallelcart.inventory.optimistic.retry.total"));
        locks.put("optimisticFailures", counterTotal("parallelcart.inventory.optimistic.failure.total"));
        locks.put("pessimisticFallbacks", counterTotal("parallelcart.inventory.pessimistic.fallback.total"));
        locks.put("reservationFailures", counterTotal("parallelcart.inventory.reservation.failure.total"));
        locks.put("inventoryLockWait", groupedTimerStats("parallelcart.lock.wait.duration", "product_id"));
        return locks;
    }

    private List<Map<String, Object>> cacheSummary() {
        List<String> caches = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("parallelcart.cache."))
                .map(meter -> meter.getId().getTag("cache"))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String cache : caches) {
            double hits = counterTotal("parallelcart.cache.hit.total", "cache", cache);
            double misses = counterTotal("parallelcart.cache.miss.total", "cache", cache);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cache", cache);
            item.put("hits", round(hits));
            item.put("misses", round(misses));
            item.put("hitRatio", hits + misses == 0 ? 0 : round(hits / (hits + misses)));
            item.put("estimatedDbQueriesAvoided", round(counterTotal(
                    "parallelcart.cache.estimated_db_queries_avoided.total",
                    "cache",
                    cache)));
            item.put("backingLoad", timerStats("parallelcart.cache.backing_load.duration", "cache", cache));
            item.put("operationLatency", timerStats("parallelcart.cache.operation.duration", "cache", cache));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> databaseSummary() {
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("connectionAcquire", timerStats("parallelcart.datasource.connection.acquire.duration"));
        database.put("queryExecutionByQuery", groupedTimerStats("parallelcart.database.query.duration", "query"));
        database.put("activeConnections", gaugeValue("parallelcart.datasource.connections.active"));
        database.put("pendingConnections", gaugeValue("parallelcart.datasource.connections.pending"));
        database.put("totalConnections", gaugeValue("parallelcart.datasource.connections.total"));
        return database;
    }

    private List<Map<String, Object>> productContentionHeatmap() {
        List<String> productIds = meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getTag("product_id"))
                .filter(productId -> productId != null && !"none".equals(productId))
                .distinct()
                .toList();
        return productIds.stream()
                .map(productId -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("productId", productId);
                    item.put("lockCount", round(counterTotal("parallelcart.lock.acquire.total", "product_id", productId)));
                    item.put("retryCount", round(counterTotal("parallelcart.inventory.optimistic.retry.total", "product_id", productId)));
                    item.put("reservationFailures", round(counterTotal(
                            "parallelcart.inventory.reservation.failure.total",
                            "product_id",
                            productId)));
                    item.put("lockWaitMs", round(totalTimerMs(
                            "parallelcart.lock.wait.duration",
                            "product_id",
                            productId)));
                    return item;
                })
                .sorted(Comparator.comparingDouble(item -> -((Number) item.get("lockWaitMs")).doubleValue()))
                .limit(10)
                .toList();
    }

    private Map<String, Object> resourceSummary() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("executorQueueWait", groupedTimerStats("parallelcart.executor.queue.wait.duration", "executor"));
        resources.put("executorTaskDuration", groupedTimerStats("parallelcart.executor.task.duration", "executor"));
        resources.put("checkoutSaturationWait", timerStats("parallelcart.checkout.saturation.wait.duration"));
        resources.put("executorActive", gaugeValues("parallelcart.executor.active", "executor"));
        resources.put("executorQueueSize", gaugeValues("parallelcart.executor.queue.size", "executor"));
        resources.put("jvmMemoryUsed", gaugeValue("jvm.memory.used"));
        resources.put("jvmMemoryCommitted", gaugeValue("jvm.memory.committed"));
        resources.put("jvmThreadsLive", gaugeValue("jvm.threads.live"));
        resources.put("processCpuUsage", gaugeValue("process.cpu.usage"));
        resources.put("systemCpuUsage", gaugeValue("system.cpu.usage"));
        resources.put("jvmGcPause", timerStats("jvm.gc.pause"));
        return resources;
    }

    private Map<String, Object> timerStats(String name, String... tagPairs) {
        List<Timer> timers = timersByNameAndTags(name, tagPairs);
        if (timers.isEmpty()) {
            return Map.of("count", 0, "meanMs", 0, "p50Ms", 0, "p95Ms", 0, "p99Ms", 0, "maxMs", 0, "totalMs", 0);
        }
        double count = timers.stream().mapToDouble(Timer::count).sum();
        double totalMs = timers.stream().mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum();
        double maxMs = timers.stream().mapToDouble(timer -> timer.max(TimeUnit.MILLISECONDS)).max().orElse(0);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", round(count));
        stats.put("meanMs", count == 0 ? 0 : round(totalMs / count));
        stats.put("p50Ms", percentileMax(timers, 0.50));
        stats.put("p95Ms", percentileMax(timers, 0.95));
        stats.put("p99Ms", percentileMax(timers, 0.99));
        stats.put("maxMs", round(maxMs));
        stats.put("totalMs", round(totalMs));
        return stats;
    }

    private Map<String, Object> timerStats(Timer timer) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("count", round(timer.count()));
        stats.put("meanMs", round(timer.mean(TimeUnit.MILLISECONDS)));
        stats.put("p50Ms", percentile(timer, 0.50));
        stats.put("p95Ms", percentile(timer, 0.95));
        stats.put("p99Ms", percentile(timer, 0.99));
        stats.put("maxMs", round(timer.max(TimeUnit.MILLISECONDS)));
        stats.put("totalMs", round(timer.totalTime(TimeUnit.MILLISECONDS)));
        return stats;
    }

    private List<Map<String, Object>> groupedTimerStats(String name, String tagKey) {
        return timersByName(name).stream()
                .map(timer -> timer.getId().getTag(tagKey))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(tagValue -> {
                    Map<String, Object> stats = new LinkedHashMap<>(timerStats(name, tagKey, tagValue));
                    stats.put(tagKey, tagValue);
                    return stats;
                })
                .toList();
    }

    private double totalTimerMs(String name, String... tagPairs) {
        return timersByNameAndTags(name, tagPairs).stream()
                .mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS))
                .sum();
    }

    private List<Timer> timersByName(String name) {
        return meterRegistry.getMeters().stream()
                .filter(meter -> name.equals(meter.getId().getName()))
                .map(meter -> meter instanceof Timer timer ? timer : null)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<Timer> timersByNameAndTags(String name, String... tagPairs) {
        return timersByName(name).stream()
                .filter(timer -> hasTags(timer, tagPairs))
                .toList();
    }

    private boolean hasTags(Meter meter, String... tagPairs) {
        for (int i = 0; i < tagPairs.length; i += 2) {
            String actual = meter.getId().getTag(tagPairs[i]);
            if (!Objects.equals(actual, tagPairs[i + 1])) {
                return false;
            }
        }
        return true;
    }

    private double counterTotal(String name, String... tagPairs) {
        return meterRegistry.getMeters().stream()
                .filter(meter -> name.equals(meter.getId().getName()))
                .filter(meter -> hasTags(meter, tagPairs))
                .map(meter -> meter instanceof Counter counter ? counter.count() : sumMeasurements(meter))
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private double sumMeasurements(Meter meter) {
        double total = 0;
        for (Measurement measurement : meter.measure()) {
            total += measurement.getValue();
        }
        return total;
    }

    private Map<String, Object> gaugeValues(String name, String tagKey) {
        Map<String, Object> values = new LinkedHashMap<>();
        meterRegistry.getMeters().stream()
                .filter(meter -> name.equals(meter.getId().getName()))
                .sorted(Comparator.comparing(meter -> Objects.toString(meter.getId().getTag(tagKey), "")))
                .forEach(meter -> values.put(meter.getId().getTag(tagKey), round(sumMeasurements(meter))));
        return values;
    }

    private double gaugeValue(String name) {
        return round(meterRegistry.getMeters().stream()
                .filter(meter -> name.equals(meter.getId().getName()))
                .mapToDouble(this::sumMeasurements)
                .sum());
    }

    private double percentileMax(List<Timer> timers, double percentile) {
        return timers.stream()
                .mapToDouble(timer -> percentile(timer, percentile))
                .max()
                .orElse(0);
    }

    private double percentile(Timer timer, double percentile) {
        for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
            if (Math.abs(value.percentile() - percentile) < 0.001) {
                return round(value.value(TimeUnit.MILLISECONDS));
            }
        }
        return 0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Impact(String name, double impactMs) {
    }
}
