package com.parallelcart.service.impl;

import com.parallelcart.config.DistributedLockProperties;
import com.parallelcart.config.DistributedLockProperties.LockSettings;
import com.parallelcart.service.DistributedLockService;
import com.parallelcart.service.DistributedLockUnavailableException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RedissonDistributedLockService implements DistributedLockService {
    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLockService.class);

    private static final String INVENTORY_LOCK_PREFIX = "inventory:product:";
    private static final String IDEMPOTENCY_LOCK_PREFIX = "checkout:idempotency:";

    private final RedissonClient redissonClient;
    private final DistributedLockProperties lockProperties;

    public RedissonDistributedLockService(
            RedissonClient redissonClient,
            DistributedLockProperties lockProperties
    ) {
        this.redissonClient = redissonClient;
        this.lockProperties = lockProperties;
    }

    @Override
    public <T> T executeWithInventoryLock(Long productId, Supplier<T> action) {
        String lockKey = INVENTORY_LOCK_PREFIX + productId;
        return executeWithLock(
                lockKey,
                lockProperties.getInventory(),
                "inventory",
                "Inventory for product " + productId + " is currently being processed. Retry shortly.",
                action
        );
    }

    @Override
    public <T> T executeWithIdempotencyLock(String idempotencyKey, Supplier<T> action) {
        String lockKey = IDEMPOTENCY_LOCK_PREFIX + idempotencyKey;
        return executeWithLock(
                lockKey,
                lockProperties.getIdempotency(),
                "idempotency",
                "Checkout with this idempotency key is already in progress.",
                action
        );
    }

    private <T> T executeWithLock(
            String lockKey,
            LockSettings settings,
            String eventPrefix,
            String failureMessage,
            Supplier<T> action
    ) {
        RLock lock = redissonClient.getLock(lockKey);
        long acquisitionStartedNanos = System.nanoTime();
        boolean acquired = tryAcquire(lock, lockKey, settings, eventPrefix, failureMessage);
        Duration waitDuration = elapsedSince(acquisitionStartedNanos);

        if (!acquired) {
            log.warn("{}_lock_failed lock_key={} wait_duration_ms={} execution_duration_ms={}",
                    eventPrefix, lockKey, waitDuration.toMillis(), 0);
            throw new DistributedLockUnavailableException(failureMessage);
        }

        log.info("{}_lock_acquired lock_key={} wait_duration_ms={} execution_duration_ms={}",
                eventPrefix, lockKey, waitDuration.toMillis(), 0);

        long executionStartedNanos = System.nanoTime();
        boolean unlockDeferred = false;
        try {
            return action.get();
        } finally {
            unlockDeferred = deferUnlockUntilTransactionCompletionIfNeeded(
                    lock, eventPrefix, lockKey, waitDuration, executionStartedNanos);
            if (!unlockDeferred) {
                unlock(lock, eventPrefix, lockKey, waitDuration, executionStartedNanos);
            }
        }
    }

    private boolean tryAcquire(
            RLock lock,
            String lockKey,
            LockSettings settings,
            String eventPrefix,
            String failureMessage
    ) {
        try {
            return lock.tryLock(
                    settings.getWaitTime().toMillis(),
                    settings.getLeaseTime().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("{}_lock_failed lock_key={} wait_duration_ms={} execution_duration_ms={}",
                    eventPrefix, lockKey, settings.getWaitTime().toMillis(), 0);
            throw new DistributedLockUnavailableException(failureMessage, ex);
        }
    }

    private boolean deferUnlockUntilTransactionCompletionIfNeeded(
            RLock lock,
            String eventPrefix,
            String lockKey,
            Duration waitDuration,
            long executionStartedNanos
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                unlock(lock, eventPrefix, lockKey, waitDuration, executionStartedNanos);
            }
        });
        return true;
    }

    private void unlock(
            RLock lock,
            String eventPrefix,
            String lockKey,
            Duration waitDuration,
            long executionStartedNanos
    ) {
        Duration executionDuration = elapsedSince(executionStartedNanos);
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info("{}_lock_released lock_key={} wait_duration_ms={} execution_duration_ms={}",
                        eventPrefix, lockKey, waitDuration.toMillis(), executionDuration.toMillis());
            } else {
                log.warn("{}_lock_release_skipped lock_key={} wait_duration_ms={} execution_duration_ms={}",
                        eventPrefix, lockKey, waitDuration.toMillis(), executionDuration.toMillis());
            }
        } catch (RuntimeException ex) {
            log.warn("{}_lock_release_failed lock_key={} wait_duration_ms={} execution_duration_ms={}",
                    eventPrefix, lockKey, waitDuration.toMillis(), executionDuration.toMillis(), ex);
        }
    }

    private Duration elapsedSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }
}
