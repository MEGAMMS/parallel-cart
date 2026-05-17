package com.parallelcart.api.controller;

import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.api.dto.CartResponse;
import com.parallelcart.api.dto.CheckoutRequest;
import com.parallelcart.api.dto.CheckoutResponse;
import com.parallelcart.api.dto.UpdateCartItemRequest;
import com.parallelcart.service.CartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/carts/{userId}")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public CartResponse getCart(@PathVariable Long userId) {
        return cartService.getCart(userId);
    }

    @PostMapping("/items")
    public CartResponse addItem(@PathVariable Long userId, @Valid @RequestBody CartItemRequest request) {
        return cartService.addItem(userId, request);
    }

    @PutMapping("/items/{itemId}")
    public CartResponse updateItem(
            @PathVariable Long userId,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateCartItemRequest request
    ) {
        return cartService.updateItem(userId, itemId, request.quantity());
    }

    @DeleteMapping("/items/{itemId}")
    public CartResponse removeItem(@PathVariable Long userId, @PathVariable Long itemId) {
        return cartService.removeItem(userId, itemId);
    }

    @PostMapping("/checkout")
    public CheckoutResponse checkout(
            @PathVariable Long userId,
            @Valid @RequestBody CheckoutRequest request
    ) {
        return cartService.checkout(userId, request.idempotencyKey());
    }
}
