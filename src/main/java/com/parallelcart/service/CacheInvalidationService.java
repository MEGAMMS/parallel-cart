package com.parallelcart.service;

import java.util.Set;

public interface CacheInvalidationService {
    void evictProductsAfterCommit(Set<Long> productIds);

    void evictCartAfterCommit(Long userId);

    void evictOrderAfterCommit(Long orderId, Long userId);
}
