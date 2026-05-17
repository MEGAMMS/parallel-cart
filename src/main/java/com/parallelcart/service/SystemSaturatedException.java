package com.parallelcart.service;

public class SystemSaturatedException extends RuntimeException {
    public SystemSaturatedException(String message) {
        super(message);
    }
}
