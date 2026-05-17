package com.parallelcart.service.impl;

import com.parallelcart.service.CheckoutSaturationGuard;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class NoopCheckoutSaturationGuard implements CheckoutSaturationGuard {
    @Override
    public <T> T execute(String operationName, ThrowingSupplier<T> action) {
        return action.get();
    }
}
