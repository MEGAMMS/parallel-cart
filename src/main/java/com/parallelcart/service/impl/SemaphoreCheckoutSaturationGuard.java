package com.parallelcart.service.impl;

import com.parallelcart.observability.BenchmarkMetricsService;
import com.parallelcart.service.CheckoutSaturationGuard;
import com.parallelcart.service.SystemSaturatedException;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SemaphoreCheckoutSaturationGuard implements CheckoutSaturationGuard {

    private final Semaphore semaphore;
    private final BenchmarkMetricsService metricsService;

    public SemaphoreCheckoutSaturationGuard(
            @Value("${app.backpressure.checkout.max-concurrent:50}") int maxConcurrentCheckouts,
            BenchmarkMetricsService metricsService) {
        this.semaphore = new Semaphore(maxConcurrentCheckouts, true);
        this.metricsService = metricsService;
    }

    @Override
    public <T> T execute(String operationName, ThrowingSupplier<T> action) {
        long waitStartedNanos = System.nanoTime();
        Tags tags = Tags.of("operation", operationName);
        if (!semaphore.tryAcquire()) {
            metricsService.recordDuration(
                    "parallelcart.checkout.saturation.wait.duration",
                    tags.and("status", "rejected"),
                    System.nanoTime() - waitStartedNanos);
            metricsService.increment("parallelcart.checkout.saturation.rejected.total", tags);
            throw new SystemSaturatedException("System saturated. Try again shortly.");
        }
        metricsService.recordDuration(
                "parallelcart.checkout.saturation.wait.duration",
                tags.and("status", "accepted"),
                System.nanoTime() - waitStartedNanos);
        metricsService.increment("parallelcart.checkout.saturation.accepted.total", tags);
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }
}
