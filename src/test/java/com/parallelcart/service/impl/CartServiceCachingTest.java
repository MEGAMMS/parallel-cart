package com.parallelcart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.api.dto.CartResponse;
import com.parallelcart.api.dto.CheckoutResponse;
import com.parallelcart.domain.model.Cart;
import com.parallelcart.domain.model.CartItem;
import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.Payment;
import com.parallelcart.domain.model.Product;
import com.parallelcart.domain.model.User;
import com.parallelcart.infra.messaging.OutboxEventService;
import com.parallelcart.infra.repository.CartItemRepository;
import com.parallelcart.infra.repository.CartRepository;
import com.parallelcart.infra.repository.InventoryRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.infra.repository.PaymentRepository;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.infra.repository.UserRepository;
import com.parallelcart.service.CacheInvalidationService;
import com.parallelcart.service.CartService;
import com.parallelcart.service.DistributedLockService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
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
@Import(CartServiceCachingTest.TestConfig.class)
class CartServiceCachingTest {

    @TestConfiguration
    @EnableCaching
    static class TestConfig {
        @Bean
        CartServiceImpl cartService(
                UserRepository userRepository,
                ProductRepository productRepository,
                InventoryRepository inventoryRepository,
                CartRepository cartRepository,
                CartItemRepository cartItemRepository,
                OrderRepository orderRepository,
                PaymentRepository paymentRepository,
                OutboxEventService outboxEventService,
                CacheInvalidationService cacheInvalidationService,
                DistributedLockService distributedLockService
        ) {
            return new CartServiceImpl(
                    userRepository,
                    productRepository,
                    inventoryRepository,
                    cartRepository,
                    cartItemRepository,
                    orderRepository,
                    paymentRepository,
                    outboxEventService,
                    cacheInvalidationService,
                    distributedLockService
            );
        }

        @Bean
        DistributedLockService distributedLockService() {
            return new DistributedLockService() {
                @Override
                public <T> T executeWithInventoryLock(Long productId, Supplier<T> action) {
                    return action.get();
                }

                @Override
                public <T> T executeWithIdempotencyLock(String idempotencyKey, Supplier<T> action) {
                    return action.get();
                }
            };
        }

        @Bean
        CacheInvalidationService cacheInvalidationService(CacheManager cacheManager) {
            return new CacheInvalidationServiceImpl(cacheManager);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("products", "carts", "orders");
        }
    }

    @Autowired
    private CartService cartService;

    @Autowired
    private CacheManager cacheManager;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private InventoryRepository inventoryRepository;

    @MockBean
    private CartRepository cartRepository;

    @MockBean
    private CartItemRepository cartItemRepository;

    @MockBean
    private OrderRepository orderRepository;

    @MockBean
    private PaymentRepository paymentRepository;

    @MockBean
    private OutboxEventService outboxEventService;

    @BeforeEach
    void clearCache() {
        for (String cacheName : List.of("products", "carts", "orders")) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    @Test
    void getCartUsesCacheForRepeatedRead() {
        User user = user(1L);
        Product product = product(10L);
        Cart cart = cart(100L, user);
        CartItem item = cartItem(1000L, cart, product, 2);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCart(cart)).thenReturn(List.of(item));

        CartResponse first = cartService.getCart(1L);
        CartResponse second = cartService.getCart(1L);

        assertEquals(first, second);
        verify(userRepository, times(1)).findById(1L);
        verify(cartRepository, times(1)).findByUser(user);
        verify(cartItemRepository, times(1)).findByCart(cart);
    }

    @Test
    void addItemInvalidatesCachedCart() {
        User user = user(1L);
        Product product = product(10L);
        Cart cart = cart(100L, user);
        CartItem existingItem = cartItem(1000L, cart, product, 1);
        CartItem addedItem = cartItem(1001L, cart, product, 3);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartItemRepository.findByCart(cart))
                .thenReturn(List.of(existingItem))
                .thenReturn(List.of(existingItem, addedItem))
                .thenReturn(List.of(existingItem, addedItem));

        cartService.getCart(1L);
        cartService.getCart(1L);
        cartService.addItem(1L, new CartItemRequest(10L, 3));
        CartResponse afterMutation = cartService.getCart(1L);

        assertEquals(2, afterMutation.items().size());
        verify(cartItemRepository, times(3)).findByCart(cart);
    }

    @Test
    void removeItemInvalidatesCachedCart() {
        User user = user(1L);
        Product product = product(10L);
        Cart cart = cart(100L, user);
        CartItem existingItem = cartItem(1000L, cart, product, 1);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartItemRepository.findById(1000L)).thenReturn(Optional.of(existingItem));
        when(cartItemRepository.findByCart(cart))
                .thenReturn(List.of(existingItem))
                .thenReturn(List.of())
                .thenReturn(List.of());

        cartService.getCart(1L);
        cartService.getCart(1L);
        cartService.removeItem(1L, 1000L);
        CartResponse afterMutation = cartService.getCart(1L);

        assertEquals(0, afterMutation.items().size());
        verify(cartItemRepository, times(3)).findByCart(cart);
    }

    @Test
    void checkoutEvictsCachedCart() {
        User user = user(1L);
        Product product = product(10L);
        Cart cart = cart(100L, user);
        CartItem item = cartItem(1000L, cart, product, 2);
        Inventory inventory = inventory(product, 10);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(cartRepository.findByUser(user)).thenReturn(Optional.of(cart));
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartItemRepository.findByCart(cart))
                .thenReturn(List.of(item))
                .thenReturn(List.of(item))
                .thenReturn(List.of());
        when(orderRepository.findByUserIdAndIdempotencyKey(1L, "idem-1")).thenReturn(Optional.empty());
        when(inventoryRepository.findByProduct(product)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.saveAndFlush(inventory)).thenReturn(inventory);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 500L);
            return order;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            ReflectionTestUtils.setField(payment, "id", 700L);
            return payment;
        });

        cartService.getCart(1L);
        CheckoutResponse checkout = cartService.checkout(1L, "idem-1");
        CartResponse afterCheckout = cartService.getCart(1L);

        assertEquals(500L, checkout.orderId());
        assertEquals(0, afterCheckout.items().size());
        verify(cartItemRepository, times(3)).findByCart(cart);
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

    private Cart cart(Long id, User user) {
        Cart cart = new Cart();
        ReflectionTestUtils.setField(cart, "id", id);
        cart.setUser(user);
        return cart;
    }

    private CartItem cartItem(Long id, Cart cart, Product product, int quantity) {
        CartItem item = new CartItem();
        ReflectionTestUtils.setField(item, "id", id);
        item.setCart(cart);
        item.setProduct(product);
        item.setQuantity(quantity);
        return item;
    }

    private Inventory inventory(Product product, int quantity) {
        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setAvailableQuantity(quantity);
        return inventory;
    }
}
