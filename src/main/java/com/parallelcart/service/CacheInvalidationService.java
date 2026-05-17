package com.parallelcart.service;

import java.util.Set;

public interface CacheInvalidationService {
    void evictProductsAfterCommit(Set<Long> productIds);
}
