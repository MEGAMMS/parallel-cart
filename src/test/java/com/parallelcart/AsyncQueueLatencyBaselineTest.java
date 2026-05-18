package com.parallelcart;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AsyncQueueLatencyBaselineTest {

    @Test
    void queuedBackgroundWorkShouldLowerCheckoutPathLatency() throws Exception {
        int samples = 8;
        long checkoutCoreWorkMs = 10;
        long invoiceWorkMs = 120;
        long notificationWorkMs = 120;

        long inlineTotalMs = 0;
        long queuedTotalMs = 0;

        ExecutorService backgroundWorkers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < samples; i++) {
                inlineTotalMs += measureMillis(() -> {
                    simulateWork(checkoutCoreWorkMs);
                    simulateWork(invoiceWorkMs);
                    simulateWork(notificationWorkMs);
                });

                List<Future<?>> futures = new ArrayList<>();
                queuedTotalMs += measureMillis(() -> {
                    simulateWork(checkoutCoreWorkMs);
                    futures.add(backgroundWorkers.submit(() -> simulateWork(invoiceWorkMs)));
                    futures.add(backgroundWorkers.submit(() -> simulateWork(notificationWorkMs)));
                });

                for (Future<?> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            backgroundWorkers.shutdown();
            assertTrue(backgroundWorkers.awaitTermination(10, TimeUnit.SECONDS), "worker shutdown timeout");
        }

        long inlineAvgMs = Math.round((double) inlineTotalMs / samples);
        long queuedAvgMs = Math.round((double) queuedTotalMs / samples);

        System.out.printf(
                "Async baseline summary: samples=%d, inlineAvgMs=%d, queuedAvgMs=%d, inlineWorkMs=%d%n",
                samples,
                inlineAvgMs,
                queuedAvgMs,
                checkoutCoreWorkMs + invoiceWorkMs + notificationWorkMs);

        assertTrue(
                queuedAvgMs < inlineAvgMs,
                "queued mode should be faster for request path latency. inlineAvgMs=" + inlineAvgMs
                        + ", queuedAvgMs=" + queuedAvgMs);
    }

    private long measureMillis(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private void simulateWork(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while simulating work", interruptedException);
        }
    }
}
