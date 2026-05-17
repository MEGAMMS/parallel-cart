package com.parallelcart.api.dto;

public record CartItemResponse(Long itemId, Long productId, String productName, Integer quantity) {
}
