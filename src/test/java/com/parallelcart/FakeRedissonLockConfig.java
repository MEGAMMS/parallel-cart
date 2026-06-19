package com.parallelcart;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeRedissonLockConfig {

    @Bean
    FakeRedissonLockControl fakeRedissonLockControl() {
        return new FakeRedissonLockControl();
    }

    @Bean
    @Primary
    RedissonClient redissonClient(FakeRedissonLockControl lockControl) {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getLock(anyString())).thenAnswer(invocation ->
                lockControl.lock(invocation.getArgument(0)));
        return redissonClient;
    }

    public static class FakeRedissonLockControl {
        private final Map<String, LockState> locks = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> acquisitionCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
        private final Map<String, Duration> delayAfterAcquireByPrefix = new ConcurrentHashMap<>();

        public void reset() {
            locks.clear();
            acquisitionCounts.clear();
            failureCounts.clear();
            delayAfterAcquireByPrefix.clear();
        }

        public void delayAfterAcquire(String keyPrefix, Duration delay) {
            delayAfterAcquireByPrefix.put(keyPrefix, delay);
        }

        public int acquisitionCount(String lockKey) {
            return acquisitionCounts.getOrDefault(lockKey, new AtomicInteger()).get();
        }

        public int failureCount(String lockKey) {
            return failureCounts.getOrDefault(lockKey, new AtomicInteger()).get();
        }

        private RLock lock(String lockKey) {
            LockState state = locks.computeIfAbsent(lockKey, ignored -> new LockState());
            RLock lock = mock(RLock.class);
            try {
                when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
                    long waitTime = invocation.getArgument(0);
                    TimeUnit timeUnit = invocation.getArgument(2);
                    boolean acquired = state.lock.tryLock(waitTime, timeUnit);

                    if (acquired) {
                        acquisitionCounts.computeIfAbsent(lockKey, ignored -> new AtomicInteger()).incrementAndGet();
                        sleep(delayFor(lockKey));
                    } else {
                        failureCounts.computeIfAbsent(lockKey, ignored -> new AtomicInteger()).incrementAndGet();
                    }

                    return acquired;
                });
            } catch (InterruptedException ex) {
                throw new IllegalStateException("Failed to configure fake Redisson lock", ex);
            }
            when(lock.isHeldByCurrentThread()).thenAnswer(invocation -> state.lock.isHeldByCurrentThread());
            doAnswer(invocation -> {
                state.lock.unlock();
                return null;
            }).when(lock).unlock();
            return lock;
        }

        private Duration delayFor(String lockKey) {
            return delayAfterAcquireByPrefix.entrySet().stream()
                    .filter(entry -> lockKey.startsWith(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(Duration.ZERO);
        }

        private void sleep(Duration delay) throws InterruptedException {
            if (!delay.isZero() && !delay.isNegative()) {
                Thread.sleep(delay.toMillis());
            }
        }

        private static class LockState {
            private final ReentrantLock lock = new ReentrantLock();
        }
    }
}
