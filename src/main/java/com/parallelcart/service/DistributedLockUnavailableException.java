package com.parallelcart.service;

public class DistributedLockUnavailableException extends IllegalStateException {

    public DistributedLockUnavailableException(String message) {
        super(message);
    }

    public DistributedLockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
