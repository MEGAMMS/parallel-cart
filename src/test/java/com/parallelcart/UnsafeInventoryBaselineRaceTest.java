package com.parallelcart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class UnsafeInventoryBaselineRaceTest {

    @Test
    void naiveSharedInventoryCanOversellUnderConcurrency() throws Exception {
        int initialStock = 5;
        int buyerCount = 12;

        ScenarioResult observed = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            ScenarioResult result = runScenario(initialStock, buyerCount);
            observed = result;
            if (result.oversold()) {
                break;
            }
        }

        if (observed == null) {
            throw new IllegalStateException("scenario result missing");
        }

        System.out.printf(
                "Unsafe baseline summary: initialStock=%d, buyers=%d, successes=%d, failures=%d, finalInventory=%d, oversold=%s%n",
                initialStock,
                buyerCount,
                observed.successes(),
                observed.failures(),
                observed.finalInventory(),
                observed.oversold());

        assertTrue(
                observed.oversold(),
                "expected oversell/inconsistency in unsafe baseline. Observed: " + observed);
    }

    private ScenarioResult runScenario(int initialStock, int buyerCount) throws InterruptedException {
        UnsafeInventory inventory = new UnsafeInventory(initialStock);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(buyerCount);
        CountDownLatch ready = new CountDownLatch(buyerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyerCount);

        for (int i = 0; i < buyerCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start barrier timeout");
                    }

                    if (inventory.tryReserveOne()) {
                        successes.incrementAndGet();
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers not ready");
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "workers not done");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "pool termination timeout");

        int successCount = successes.get();
        int failureCount = failures.get();
        int finalInventory = inventory.available();
        boolean oversold = successCount > initialStock || finalInventory < 0;

        return new ScenarioResult(successCount, failureCount, finalInventory, oversold);
    }

    private record ScenarioResult(int successes, int failures, int finalInventory, boolean oversold) {
    }

    private static final class UnsafeInventory {
        private int availableQuantity;

        private UnsafeInventory(int availableQuantity) {
            this.availableQuantity = availableQuantity;
        }

        private boolean tryReserveOne() {
            if (availableQuantity <= 0) {
                return false;
            }

            int snapshot = availableQuantity;
            try {
                Thread.sleep(2);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
            availableQuantity = snapshot - 1;
            return true;
        }

        private int available() {
            return availableQuantity;
        }
    }
}
