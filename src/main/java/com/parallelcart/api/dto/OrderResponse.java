package com.parallelcart.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record OrderResponse(
        Long orderId,
        Long userId,
        String status,
        BigDecimal totalAmount,
        List<OrderItemResponse> items
) {
}
