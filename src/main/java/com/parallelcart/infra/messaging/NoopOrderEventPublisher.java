package com.parallelcart.infra.messaging;

import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class NoopOrderEventPublisher implements OrderEventPublisher {

    @Override
    public void publishOrderCreated(OrderCreatedEvent event) {
        // No-op for tests to avoid external Kafka dependency.
    }
}
