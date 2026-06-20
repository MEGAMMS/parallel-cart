package com.parallelcart.observability;

import io.micrometer.core.instrument.Tags;
import java.util.concurrent.Callable;
import org.springframework.cache.Cache;

public class InstrumentedCache implements Cache {

    private final Cache delegate;
    private final BenchmarkMetricsService metricsService;
    private final boolean disabled;

    public InstrumentedCache(Cache delegate, BenchmarkMetricsService metricsService, boolean disabled) {
        this.delegate = delegate;
        this.metricsService = metricsService;
        this.disabled = disabled;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    public ValueWrapper get(Object key) {
        if (isNoOpCache()) {
            long startNanos = System.nanoTime();
            ValueWrapper value = delegate.get(key);
            record("get", "disabled", startNanos);
            metricsService.increment("parallelcart.cache.lookup.total", tags("disabled"));
            return value;
        }
        long startNanos = System.nanoTime();
        ValueWrapper value = delegate.get(key);
        String result = value == null ? "miss" : "hit";
        record("get", result, startNanos);
        metricsService.increment("parallelcart.cache.lookup.total", tags(result));
        if (value == null) {
            metricsService.increment("parallelcart.cache.miss.total", tags(result));
        } else {
            metricsService.increment("parallelcart.cache.hit.total", tags(result));
            metricsService.increment("parallelcart.cache.estimated_db_queries_avoided.total", tags(result));
        }
        return value;
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        if (isNoOpCache()) {
            long startNanos = System.nanoTime();
            T value = delegate.get(key, type);
            record("get_typed", "disabled", startNanos);
            metricsService.increment("parallelcart.cache.lookup.total", tags("disabled"));
            return value;
        }
        long startNanos = System.nanoTime();
        T value = delegate.get(key, type);
        String result = value == null ? "miss" : "hit";
        record("get_typed", result, startNanos);
        metricsService.increment("parallelcart.cache.lookup.total", tags(result));
        if (value == null) {
            metricsService.increment("parallelcart.cache.miss.total", tags(result));
        } else {
            metricsService.increment("parallelcart.cache.hit.total", tags(result));
            metricsService.increment("parallelcart.cache.estimated_db_queries_avoided.total", tags(result));
        }
        return value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        if (isNoOpCache()) {
            long startNanos = System.nanoTime();
            try {
                T value = delegate.get(key, valueLoader);
                record("load", "disabled", startNanos);
                metricsService.increment("parallelcart.cache.load.total", tags("disabled"));
                return value;
            } catch (RuntimeException ex) {
                record("load", "error", startNanos);
                metricsService.increment("parallelcart.cache.load.total", tags("error"));
                throw ex;
            }
        }
        ValueWrapper existing = get(key);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            T value = (T) existing.get();
            return value;
        }

        long startNanos = System.nanoTime();
        try {
            T value = delegate.get(key, valueLoader);
            record("load", "success", startNanos);
            metricsService.increment("parallelcart.cache.load.total", tags("success"));
            return value;
        } catch (RuntimeException ex) {
            record("load", "error", startNanos);
            metricsService.increment("parallelcart.cache.load.total", tags("error"));
            throw ex;
        }
    }

    @Override
    public void put(Object key, Object value) {
        long startNanos = System.nanoTime();
        delegate.put(key, value);
        record("put", "success", startNanos);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        long startNanos = System.nanoTime();
        ValueWrapper wrapper = delegate.putIfAbsent(key, value);
        record("put_if_absent", wrapper == null ? "inserted" : "present", startNanos);
        return wrapper;
    }

    @Override
    public void evict(Object key) {
        long startNanos = System.nanoTime();
        delegate.evict(key);
        record("evict", "success", startNanos);
        metricsService.increment("parallelcart.cache.evict.total", tags("success"));
    }

    @Override
    public boolean evictIfPresent(Object key) {
        long startNanos = System.nanoTime();
        boolean evicted = delegate.evictIfPresent(key);
        record("evict_if_present", evicted ? "evicted" : "absent", startNanos);
        metricsService.increment("parallelcart.cache.evict.total", tags(evicted ? "evicted" : "absent"));
        return evicted;
    }

    @Override
    public void clear() {
        long startNanos = System.nanoTime();
        delegate.clear();
        record("clear", "success", startNanos);
    }

    @Override
    public boolean invalidate() {
        long startNanos = System.nanoTime();
        boolean invalidated = delegate.invalidate();
        record("invalidate", invalidated ? "invalidated" : "empty", startNanos);
        return invalidated;
    }

    private void record(String operation, String result, long startNanos) {
        metricsService.recordDuration(
                "parallelcart.cache.operation.duration",
                Tags.of("cache", getName(), "operation", operation, "result", result),
                System.nanoTime() - startNanos);
    }

    private Tags tags(String result) {
        return Tags.of("cache", getName(), "result", result);
    }

    private boolean isNoOpCache() {
        return disabled || delegate.getClass().getName().contains("NoOpCache");
    }
}
