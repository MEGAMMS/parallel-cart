package com.parallelcart.service;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CheckoutCapacityGuard {

    private final Semaphore permits;
    private final int maxConcurrent;

    public CheckoutCapacityGuard(@Value("${app.checkout.max-concurrent:8}") int maxConcurrent) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("app.checkout.max-concurrent must be > 0");
        }
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent, true);
    }

    public <T> T runWithPermit(Supplier<T> action) {
        if (!permits.tryAcquire()) {
            throw new CheckoutCapacityExceededException(
                    "Checkout capacity exceeded. Max concurrent checkouts: " + maxConcurrent);
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }
}
