package com.parallelcart.service;

public interface CheckoutSaturationGuard {
    <T> T execute(String operationName, ThrowingSupplier<T> action);

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws RuntimeException;
    }
}
