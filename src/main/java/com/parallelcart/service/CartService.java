package com.parallelcart.service;

import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.api.dto.CartResponse;
import com.parallelcart.api.dto.CheckoutResponse;

public interface CartService {
    CartResponse addItem(Long userId, CartItemRequest request);

    CartResponse updateItem(Long userId, Long itemId, Integer quantity);

    CartResponse removeItem(Long userId, Long itemId);

    CartResponse clearCart(Long userId);

    CartResponse getCart(Long userId);

    CheckoutResponse checkout(Long userId, String idempotencyKey);
}
