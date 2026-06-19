package com.parallelcart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parallelcart.api.dto.OrderResponse;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.OrderItem;
import com.parallelcart.domain.model.Product;
import com.parallelcart.domain.model.User;
import com.parallelcart.domain.model.enums.OrderStatus;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.service.CacheInvalidationService;
import com.parallelcart.service.OrderService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

@SpringJUnitConfig
@Import(OrderServiceCachingTest.TestConfig.class)
class OrderServiceCachingTest {

    @TestConfiguration
    @EnableCaching
    static class TestConfig {
        @Bean
        OrderServiceImpl orderService(
                OrderRepository orderRepository,
                CacheInvalidationService cacheInvalidationService
        ) {
            return new OrderServiceImpl(orderRepository, cacheInvalidationService);
        }

        @Bean
        CacheInvalidationService cacheInvalidationService(CacheManager cacheManager) {
            return new CacheInvalidationServiceImpl(cacheManager);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("orders");
        }
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private CacheManager cacheManager;

    @MockBean
    private OrderRepository orderRepository;

    @BeforeEach
    void clearCache() {
        Cache cache = cacheManager.getCache("orders");
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    void getOrderUsesCacheForRepeatedRead() {
        Order order = order(100L, user(1L), OrderStatus.PAID);

        when(orderRepository.findByIdWithItems(100L)).thenReturn(Optional.of(order));

        OrderResponse first = orderService.getOrder(100L);
        OrderResponse second = orderService.getOrder(100L);

        assertEquals(first, second);
        verify(orderRepository, times(1)).findByIdWithItems(100L);
    }

    @Test
    void getOrdersByUserUsesCacheForRepeatedRead() {
        Order order = order(100L, user(1L), OrderStatus.PAID);

        when(orderRepository.findByUserIdWithItemsOrderByIdDesc(1L)).thenReturn(List.of(order));

        List<OrderResponse> first = orderService.getOrdersByUser(1L);
        List<OrderResponse> second = orderService.getOrdersByUser(1L);

        assertEquals(first, second);
        verify(orderRepository, times(1)).findByUserIdWithItemsOrderByIdDesc(1L);
    }

    @Test
    void orderStatusUpdateRefreshesSingleOrderCache() {
        Order order = order(100L, user(1L), OrderStatus.PAID);

        when(orderRepository.findByIdWithItems(100L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        OrderResponse cached = orderService.getOrder(100L);
        OrderResponse updated = orderService.updateOrderStatus(100L, OrderStatus.CANCELLED);
        OrderResponse reloaded = orderService.getOrder(100L);
        OrderResponse cachedAgain = orderService.getOrder(100L);

        assertEquals("PAID", cached.status());
        assertEquals("CANCELLED", updated.status());
        assertEquals("CANCELLED", reloaded.status());
        assertEquals(reloaded, cachedAgain);
        verify(orderRepository, times(3)).findByIdWithItems(100L);
    }

    @Test
    void orderStatusUpdateInvalidatesUserOrderHistoryCache() {
        User user = user(1L);
        Order order = order(100L, user, OrderStatus.PAID);

        when(orderRepository.findByIdWithItems(100L)).thenReturn(Optional.of(order));
        when(orderRepository.findByUserIdWithItemsOrderByIdDesc(1L)).thenReturn(List.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        List<OrderResponse> cachedHistory = orderService.getOrdersByUser(1L);
        orderService.updateOrderStatus(100L, OrderStatus.CANCELLED);
        List<OrderResponse> reloadedHistory = orderService.getOrdersByUser(1L);

        assertEquals("PAID", cachedHistory.get(0).status());
        assertEquals("CANCELLED", reloadedHistory.get(0).status());
        verify(orderRepository, times(2)).findByUserIdWithItemsOrderByIdDesc(1L);
    }

    private User user(Long id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("user-" + id + "@parallelcart.local");
        user.setPasswordHash("hash");
        return user;
    }

    private Product product(Long id) {
        Product product = new Product();
        ReflectionTestUtils.setField(product, "id", id);
        product.setSku("SKU-" + id);
        product.setName("Product " + id);
        product.setDescription("Test product");
        product.setPrice(BigDecimal.TEN);
        product.setActive(true);
        return product;
    }

    private Order order(Long id, User user, OrderStatus status) {
        Product product = product(10L);
        Order order = new Order();
        ReflectionTestUtils.setField(order, "id", id);
        order.setUser(user);
        order.setStatus(status);
        order.setTotalAmount(BigDecimal.valueOf(20));
        order.setIdempotencyKey("idem-" + id);

        OrderItem item = new OrderItem();
        ReflectionTestUtils.setField(item, "id", 1000L);
        item.setOrder(order);
        item.setProduct(product);
        item.setQuantity(2);
        item.setUnitPrice(BigDecimal.TEN);
        order.getItems().add(item);

        return order;
    }
}
