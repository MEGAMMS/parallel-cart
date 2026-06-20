package com.parallelcart.service.impl;

import com.parallelcart.observability.BenchmarkMetricsService;
import com.parallelcart.service.CacheInvalidationService;
import io.micrometer.core.instrument.Tags;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CacheInvalidationServiceImpl implements CacheInvalidationService {

    private static final String PRODUCTS_CACHE = "products";
    private static final String CARTS_CACHE = "carts";
    private static final String ORDERS_CACHE = "orders";

    private final CacheManager cacheManager;
    private final BenchmarkMetricsService metricsService;

    public CacheInvalidationServiceImpl(CacheManager cacheManager, BenchmarkMetricsService metricsService) {
        this.cacheManager = cacheManager;
        this.metricsService = metricsService;
    }

    @Override
    public void evictProductsAfterCommit(Set<Long> productIds) {
        runAfterCommit(() -> evictProductsNow(productIds));
    }

    @Override
    public void evictCartAfterCommit(Long userId) {
        runAfterCommit(() -> evictCartNow(userId));
    }

    @Override
    public void evictOrderAfterCommit(Long orderId, Long userId) {
        runAfterCommit(() -> evictOrderNow(orderId, userId));
    }

    private void runAfterCommit(Runnable cacheWork) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cacheWork.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheWork.run();
            }
        });
    }

    private void evictProductsNow(Set<Long> productIds) {
        long startedNanos = System.nanoTime();
        Cache cache = cacheManager.getCache(PRODUCTS_CACHE);
        if (cache == null) {
            return;
        }
        cache.evict("all");
        for (Long productId : productIds) {
            cache.evict("id:" + productId);
        }
        metricsService.recordDuration(
                "parallelcart.cache.invalidation.duration",
                Tags.of("cache", PRODUCTS_CACHE),
                System.nanoTime() - startedNanos);
        metricsService.increment("parallelcart.cache.invalidation.total", Tags.of("cache", PRODUCTS_CACHE));
    }

    private void evictCartNow(Long userId) {
        long startedNanos = System.nanoTime();
        Cache cache = cacheManager.getCache(CARTS_CACHE);
        if (cache == null || userId == null) {
            return;
        }
        cache.evict("user:" + userId);
        metricsService.recordDuration(
                "parallelcart.cache.invalidation.duration",
                Tags.of("cache", CARTS_CACHE),
                System.nanoTime() - startedNanos);
        metricsService.increment("parallelcart.cache.invalidation.total", Tags.of("cache", CARTS_CACHE));
    }

    private void evictOrderNow(Long orderId, Long userId) {
        long startedNanos = System.nanoTime();
        Cache cache = cacheManager.getCache(ORDERS_CACHE);
        if (cache == null) {
            return;
        }
        if (orderId != null) {
            cache.evict("id:" + orderId);
        }
        if (userId != null) {
            cache.evict("user:" + userId);
        }
        metricsService.recordDuration(
                "parallelcart.cache.invalidation.duration",
                Tags.of("cache", ORDERS_CACHE),
                System.nanoTime() - startedNanos);
        metricsService.increment("parallelcart.cache.invalidation.total", Tags.of("cache", ORDERS_CACHE));
    }
}
