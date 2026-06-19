package com.parallelcart.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.distributed-lock")
public class DistributedLockProperties {

    private final LockSettings inventory = new LockSettings(Duration.ofSeconds(2), Duration.ofSeconds(10));
    private final LockSettings idempotency = new LockSettings(Duration.ZERO, Duration.ofSeconds(15));

    public LockSettings getInventory() {
        return inventory;
    }

    public LockSettings getIdempotency() {
        return idempotency;
    }

    public static class LockSettings {
        private Duration waitTime;
        private Duration leaseTime;

        public LockSettings(Duration waitTime, Duration leaseTime) {
            this.waitTime = waitTime;
            this.leaseTime = leaseTime;
        }

        public Duration getWaitTime() {
            return waitTime;
        }

        public void setWaitTime(Duration waitTime) {
            this.waitTime = waitTime;
        }

        public Duration getLeaseTime() {
            return leaseTime;
        }

        public void setLeaseTime(Duration leaseTime) {
            this.leaseTime = leaseTime;
        }
    }
}
