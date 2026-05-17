package com.parallelcart.infra.messaging;

import com.parallelcart.infra.messaging.events.OrderCreatedEvent;

public interface OrderEventPublisher {
    void publishOrderCreated(OrderCreatedEvent event);
}
