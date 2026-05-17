package com.parallelcart.service.impl;

import com.parallelcart.service.CheckoutSaturationGuard;
import com.parallelcart.service.SystemSaturatedException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SemaphoreCheckoutSaturationGuard implements CheckoutSaturationGuard {

    private final Semaphore semaphore;

    public SemaphoreCheckoutSaturationGuard(
            @Value("${app.backpressure.checkout.max-concurrent:50}") int maxConcurrentCheckouts) {
        this.semaphore = new Semaphore(maxConcurrentCheckouts, true);
    }

    @Override
    public <T> T execute(String operationName, ThrowingSupplier<T> action) {
        if (!semaphore.tryAcquire()) {
            throw new SystemSaturatedException("System saturated. Try again shortly.");
        }
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }
}
