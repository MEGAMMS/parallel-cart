package com.parallelcart.service.impl;

import com.parallelcart.api.dto.OrderItemResponse;
import com.parallelcart.api.dto.OrderResponse;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.OrderItem;
import com.parallelcart.domain.model.enums.OrderStatus;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.service.CacheInvalidationService;
import com.parallelcart.service.OrderService;
import java.util.Comparator;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CacheInvalidationService cacheInvalidationService;

    public OrderServiceImpl(
            OrderRepository orderRepository,
            CacheInvalidationService cacheInvalidationService
    ) {
        this.orderRepository = orderRepository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "orders", key = "'id:' + #orderId", sync = true)
    public OrderResponse getOrder(Long orderId) {
        return toOrderResponse(orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId)));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "orders", key = "'user:' + #userId", sync = true)
    public List<OrderResponse> getOrdersByUser(Long userId) {
        return orderRepository.findByUserIdWithItemsOrderByIdDesc(userId).stream()
                .map(this::toOrderResponse)
                .toList();
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, OrderStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Order status is required");
        }
        return changeOrderStatus(orderId, status);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        return changeOrderStatus(orderId, OrderStatus.CANCELLED);
    }

    private OrderResponse changeOrderStatus(Long orderId, OrderStatus status) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        order.setStatus(status);
        order = orderRepository.save(order);
        cacheInvalidationService.evictOrderAfterCommit(order.getId(), order.getUser().getId());
        return toOrderResponse(order);
    }

    private OrderResponse toOrderResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(item -> new OrderItemResponse(
                        item.getId(),
                        item.getProduct().getId(),
                        item.getProduct().getName(),
                        item.getQuantity(),
                        item.getUnitPrice()
                ))
                .toList();

        return new OrderResponse(
                order.getId(),
                order.getUser().getId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                items
        );
    }
}
