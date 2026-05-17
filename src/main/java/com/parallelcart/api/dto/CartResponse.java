package com.parallelcart.api.dto;

import java.util.List;

public record CartResponse(Long cartId, Long userId, List<CartItemResponse> items) {
}
