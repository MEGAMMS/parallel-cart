package com.parallelcart.service;

import java.util.function.Supplier;

public interface DistributedLockService {

    <T> T executeWithInventoryLock(Long productId, Supplier<T> action);

    <T> T executeWithIdempotencyLock(String idempotencyKey, Supplier<T> action);

    default void executeWithInventoryLock(Long productId, Runnable action) {
        executeWithInventoryLock(productId, () -> {
            action.run();
            return null;
        });
    }
}
