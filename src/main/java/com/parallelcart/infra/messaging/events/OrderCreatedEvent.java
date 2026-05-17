package com.parallelcart.infra.messaging.events;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderCreatedEvent(
        Long orderId,
        Long userId,
        Long paymentId,
        BigDecimal totalAmount,
        String status,
        Instant createdAt
) {
}
