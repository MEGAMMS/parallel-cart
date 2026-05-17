package com.parallelcart.api.dto;

import java.math.BigDecimal;

public record CheckoutResponse(Long orderId, Long paymentId, BigDecimal totalAmount, String status) {
}
