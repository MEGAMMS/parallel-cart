package com.parallelcart.observability;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

public class InstrumentedCacheManager implements CacheManager {

    private final CacheManager delegate;
    private final BenchmarkMetricsService metricsService;
    private final Map<String, Cache> cacheWrappers = new ConcurrentHashMap<>();
    private final boolean disabled;

    public InstrumentedCacheManager(CacheManager delegate, BenchmarkMetricsService metricsService, boolean disabled) {
        this.delegate = delegate;
        this.metricsService = metricsService;
        this.disabled = disabled || delegate.getClass().getName().contains("NoOpCacheManager");
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        if (cache == null) {
            return null;
        }
        return cacheWrappers.computeIfAbsent(name, ignored -> new InstrumentedCache(cache, metricsService, disabled));
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }
}
