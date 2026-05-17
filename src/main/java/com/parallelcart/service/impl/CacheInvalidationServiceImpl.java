package com.parallelcart.service.impl;

import com.parallelcart.service.CacheInvalidationService;
import java.util.Set;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CacheInvalidationServiceImpl implements CacheInvalidationService {

    private static final String PRODUCTS_CACHE = "products";

    private final CacheManager cacheManager;

    public CacheInvalidationServiceImpl(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void evictProductsAfterCommit(Set<Long> productIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictNow(productIds);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictNow(productIds);
            }
        });
    }

    private void evictNow(Set<Long> productIds) {
        Cache cache = cacheManager.getCache(PRODUCTS_CACHE);
        if (cache == null) {
            return;
        }
        cache.evict("all");
        for (Long productId : productIds) {
            cache.evict("id:" + productId);
        }
    }
}
