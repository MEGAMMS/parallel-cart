package com.parallelcart.api.controller;

import com.parallelcart.api.dto.OrderResponse;
import com.parallelcart.api.dto.UpdateOrderStatusRequest;
import com.parallelcart.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable Long orderId) {
        return orderService.getOrder(orderId);
    }

    @GetMapping("/users/{userId}")
    public List<OrderResponse> getOrdersByUser(@PathVariable Long userId) {
        return orderService.getOrdersByUser(userId);
    }

    @PatchMapping("/{orderId}/status")
    public OrderResponse updateOrderStatus(
            @PathVariable Long orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request
    ) {
        return orderService.updateOrderStatus(orderId, request.status());
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancelOrder(@PathVariable Long orderId) {
        return orderService.cancelOrder(orderId);
    }
}
