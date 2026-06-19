package com.parallelcart.api.dto;

import com.parallelcart.domain.model.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {
}
