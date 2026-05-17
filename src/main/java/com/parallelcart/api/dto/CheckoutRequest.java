package com.parallelcart.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CheckoutRequest(@NotBlank String idempotencyKey) {
}
