package com.parallelcart.service;

import com.parallelcart.api.dto.OrderResponse;
import com.parallelcart.domain.model.enums.OrderStatus;
import java.util.List;

public interface OrderService {
    OrderResponse getOrder(Long orderId);

    List<OrderResponse> getOrdersByUser(Long userId);

    OrderResponse updateOrderStatus(Long orderId, OrderStatus status);

    OrderResponse cancelOrder(Long orderId);
}
