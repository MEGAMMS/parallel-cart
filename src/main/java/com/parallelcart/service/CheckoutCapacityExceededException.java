package com.parallelcart.service;

public class CheckoutCapacityExceededException extends RuntimeException {

    public CheckoutCapacityExceededException(String message) {
        super(message);
    }
}
