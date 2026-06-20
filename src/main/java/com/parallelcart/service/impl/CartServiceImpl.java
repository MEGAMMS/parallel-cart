package com.parallelcart.service.impl;

import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.api.dto.CartItemResponse;
import com.parallelcart.api.dto.CartResponse;
import com.parallelcart.api.dto.CheckoutResponse;
import com.parallelcart.domain.model.Cart;
import com.parallelcart.domain.model.CartItem;
import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.OrderItem;
import com.parallelcart.domain.model.Payment;
import com.parallelcart.domain.model.Product;
import com.parallelcart.domain.model.User;
import com.parallelcart.domain.model.enums.OrderStatus;
import com.parallelcart.domain.model.enums.PaymentStatus;
import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import com.parallelcart.infra.messaging.OutboxEventService;
import com.parallelcart.infra.repository.CartItemRepository;
import com.parallelcart.infra.repository.CartRepository;
import com.parallelcart.infra.repository.InventoryRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.infra.repository.PaymentRepository;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.infra.repository.UserRepository;
import com.parallelcart.observability.BenchmarkMetricsService;
import com.parallelcart.service.CacheInvalidationService;
import com.parallelcart.service.CartService;
import com.parallelcart.service.DistributedLockService;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CartServiceImpl implements CartService {
    private static final int INVENTORY_RETRY_MAX_ATTEMPTS = 3;

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxEventService outboxEventService;
    private final CacheInvalidationService cacheInvalidationService;
    private final DistributedLockService distributedLockService;
    private final BenchmarkMetricsService metricsService;

    public CartServiceImpl(
            UserRepository userRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            OutboxEventService outboxEventService,
            CacheInvalidationService cacheInvalidationService,
            DistributedLockService distributedLockService,
            BenchmarkMetricsService metricsService
    ) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.outboxEventService = outboxEventService;
        this.cacheInvalidationService = cacheInvalidationService;
        this.distributedLockService = distributedLockService;
        this.metricsService = metricsService;
    }

    @Override
    @Transactional
    public CartResponse addItem(Long userId, CartItemRequest request) {
        User user = getUser(userId);
        Product product = getProduct(request.productId());
        Cart cart = getOrCreateCart(user);

        CartItem cartItem = new CartItem();
        cartItem.setCart(cart);
        cartItem.setProduct(product);
        cartItem.setQuantity(request.quantity());
        cartItemRepository.save(cartItem);

        cart.touch();
        cartRepository.save(cart);
        cacheInvalidationService.evictCartAfterCommit(userId);
        return toCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse updateItem(Long userId, Long itemId, Integer quantity) {
        Cart cart = getOrCreateCart(getUser(userId));
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Cart item not found: " + itemId));

        if (!item.getCart().getId().equals(cart.getId())) {
            throw new IllegalArgumentException("Cart item does not belong to user cart");
        }

        item.setQuantity(quantity);
        cartItemRepository.save(item);
        cart.touch();
        cartRepository.save(cart);
        cacheInvalidationService.evictCartAfterCommit(userId);
        return toCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long userId, Long itemId) {
        Cart cart = getOrCreateCart(getUser(userId));
        CartItem item = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Cart item not found: " + itemId));

        if (!item.getCart().getId().equals(cart.getId())) {
            throw new IllegalArgumentException("Cart item does not belong to user cart");
        }

        cartItemRepository.delete(item);
        cart.touch();
        cartRepository.save(cart);
        cacheInvalidationService.evictCartAfterCommit(userId);
        return toCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse clearCart(Long userId) {
        Cart cart = getOrCreateCart(getUser(userId));
        cartItemRepository.deleteAll(cartItemRepository.findByCart(cart));
        cart.touch();
        cartRepository.save(cart);
        cacheInvalidationService.evictCartAfterCommit(userId);
        return toCartResponse(cart);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "carts", key = "'user:' + #userId", sync = true)
    public CartResponse getCart(Long userId) {
        return metricsService.time(
                "parallelcart.cache.backing_load.duration",
                Tags.of("cache", "carts", "operation", "get_by_user"),
                () -> toCartResponse(getOrCreateCart(getUser(userId))));
    }

    @Override
    @Transactional
    public CheckoutResponse checkout(Long userId, String idempotencyKey) {
        return metricsService.time(
                "parallelcart.checkout.total.duration",
                Tags.of("operation", "checkout"),
                () -> distributedLockService.executeWithIdempotencyLock(
                        idempotencyKey,
                        () -> checkoutWithIdempotencyLock(userId, idempotencyKey))
        );
    }

    private CheckoutResponse checkoutWithIdempotencyLock(Long userId, String idempotencyKey) {
        long cartLoadStartedNanos = System.nanoTime();
        User user = getUser(userId);

        Order existingOrder = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).orElse(null);
        if (existingOrder != null) {
            Payment existingPayment = paymentRepository.findFirstByOrderId(existingOrder.getId()).orElseThrow(
                    () -> new IllegalStateException("Payment missing for existing idempotent order " + existingOrder.getId())
            );
            recordCheckoutPhase("cart_load", cartLoadStartedNanos);
            metricsService.increment("parallelcart.checkout.idempotent_replay.total", Tags.empty());
            return new CheckoutResponse(
                    existingOrder.getId(),
                    existingPayment.getId(),
                    existingOrder.getTotalAmount(),
                    existingOrder.getStatus().name()
            );
        }

        Cart cart = getOrCreateCart(user);
        List<CartItem> items = cartItemRepository.findByCart(cart);
        recordCheckoutPhase("cart_load", cartLoadStartedNanos);

        if (items.isEmpty()) {
            metricsService.increment("parallelcart.checkout.empty_cart.total", Tags.empty());
            throw new IllegalStateException("Cart is empty");
        }
        Set<Long> touchedProductIds = items.stream()
                .map(item -> item.getProduct().getId())
                .collect(Collectors.toSet());

        BigDecimal total = BigDecimal.ZERO;
        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);

        long inventoryStartedNanos = System.nanoTime();
        for (CartItem cartItem : items) {
            distributedLockService.executeWithInventoryLock(
                    cartItem.getProduct().getId(),
                    () -> reserveInventoryWithRetry(cartItem.getProduct(), cartItem.getQuantity())
            );

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(cartItem.getProduct());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setUnitPrice(cartItem.getProduct().getPrice());
            order.getItems().add(orderItem);

            total = total.add(cartItem.getProduct().getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }
        recordCheckoutPhase("inventory_reservation", inventoryStartedNanos);

        if (total.signum() <= 0) {
            metricsService.increment("parallelcart.checkout.invalid_total.total", Tags.empty());
            throw new IllegalStateException("Invalid checkout total");
        }

        long orderStartedNanos = System.nanoTime();
        order.setIdempotencyKey(idempotencyKey);
        order.setTotalAmount(total);
        order.setStatus(OrderStatus.PAID);
        Order orderToSave = order;
        order = metricsService.time(
                "parallelcart.database.query.duration",
                Tags.of("query", "order_save"),
                () -> orderRepository.save(orderToSave));

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(total);
        payment.setExternalRef("PAY-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
        payment.setStatus(PaymentStatus.CAPTURED);
        Payment paymentToSave = payment;
        payment = metricsService.time(
                "parallelcart.database.query.duration",
                Tags.of("query", "payment_save"),
                () -> paymentRepository.save(paymentToSave));

        metricsService.time(
                "parallelcart.database.query.duration",
                Tags.of("query", "cart_items_delete"),
                () -> cartItemRepository.deleteAll(items));
        cart.touch();
        metricsService.time(
                "parallelcart.database.query.duration",
                Tags.of("query", "cart_save"),
                () -> cartRepository.save(cart));
        recordCheckoutPhase("order_creation", orderStartedNanos);

        long outboxStartedNanos = System.nanoTime();
        outboxEventService.enqueueOrderCreated(new OrderCreatedEvent(
                order.getId(),
                user.getId(),
                payment.getId(),
                total,
                order.getStatus().name(),
                Instant.now()
        ));
        recordCheckoutPhase("outbox_write", outboxStartedNanos);

        long cacheStartedNanos = System.nanoTime();
        cacheInvalidationService.evictCartAfterCommit(userId);
        cacheInvalidationService.evictOrderAfterCommit(order.getId(), userId);
        cacheInvalidationService.evictProductsAfterCommit(touchedProductIds);
        recordCheckoutPhase("cache_invalidation", cacheStartedNanos);

        return new CheckoutResponse(order.getId(), payment.getId(), total, order.getStatus().name());
    }

    private void reserveInventoryWithRetry(Product product, Integer requestedQuantity) {
        Tags productTags = Tags.of("product_id", String.valueOf(product.getId()));
        metricsService.increment("parallelcart.inventory.reservation.total", productTags);
        ObjectOptimisticLockingFailureException lastConflict = null;
        int retries = 0;
        for (int attempt = 1; attempt <= INVENTORY_RETRY_MAX_ATTEMPTS; attempt++) {
            Inventory inventory = metricsService.time(
                            "parallelcart.database.query.duration",
                            Tags.of("query", "inventory_find", "product_id", String.valueOf(product.getId())),
                            () -> inventoryRepository.findByProduct(product))
                    .orElseThrow(() -> new IllegalStateException("Inventory missing for product " + product.getId()));

            if (inventory.getAvailableQuantity() < requestedQuantity) {
                metricsService.increment("parallelcart.inventory.reservation.failure.total",
                        productTags.and("reason", "insufficient_inventory"));
                throw new IllegalStateException("Insufficient inventory for product " + product.getId());
            }

            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - requestedQuantity);
            try {
                metricsService.time(
                        "parallelcart.database.query.duration",
                        Tags.of("query", "inventory_save_flush", "product_id", String.valueOf(product.getId())),
                        () -> inventoryRepository.saveAndFlush(inventory));
                metricsService.recordDistribution(
                        "parallelcart.inventory.optimistic.retry.count",
                        productTags,
                        retries);
                return;
            } catch (ObjectOptimisticLockingFailureException ex) {
                lastConflict = ex;
                retries++;
                metricsService.increment("parallelcart.inventory.optimistic.retry.total", productTags);
                sleepWithJitter(product.getId());
            }
        }

        metricsService.increment("parallelcart.inventory.pessimistic.fallback.total", productTags);
        metricsService.recordDistribution(
                "parallelcart.inventory.optimistic.retry.count",
                productTags,
                retries);
        reserveInventoryWithPessimisticLock(product, requestedQuantity, lastConflict);
    }

    private void reserveInventoryWithPessimisticLock(
            Product product,
            Integer requestedQuantity,
            ObjectOptimisticLockingFailureException lastConflict
    ) {
        Tags productTags = Tags.of("product_id", String.valueOf(product.getId()));
        long lockWaitStartedNanos = System.nanoTime();
        Inventory lockedInventory = metricsService.time(
                        "parallelcart.database.query.duration",
                        Tags.of("query", "inventory_find_for_update", "product_id", String.valueOf(product.getId())),
                        () -> inventoryRepository.findByProductForUpdate(product))
                .orElseThrow(() -> new IllegalStateException("Inventory missing for product " + product.getId()));
        metricsService.recordDuration(
                "parallelcart.inventory.pessimistic.lock.wait.duration",
                productTags,
                System.nanoTime() - lockWaitStartedNanos);
        long lockHoldStartedNanos = System.nanoTime();
        recordAfterTransactionCompletion(
                "parallelcart.inventory.pessimistic.lock.hold.duration",
                productTags,
                lockHoldStartedNanos);

        if (lockedInventory.getAvailableQuantity() < requestedQuantity) {
            metricsService.increment("parallelcart.inventory.reservation.failure.total",
                    productTags.and("reason", "insufficient_inventory"));
            throw new IllegalStateException("Insufficient inventory for product " + product.getId());
        }

        lockedInventory.setAvailableQuantity(lockedInventory.getAvailableQuantity() - requestedQuantity);
        try {
            metricsService.time(
                    "parallelcart.database.query.duration",
                    Tags.of("query", "inventory_pessimistic_save_flush", "product_id", String.valueOf(product.getId())),
                    () -> inventoryRepository.saveAndFlush(lockedInventory));
        } catch (ObjectOptimisticLockingFailureException ex) {
            if (lastConflict != null) {
                ex.addSuppressed(lastConflict);
            }
            metricsService.increment("parallelcart.inventory.optimistic.failure.total", productTags);
            throw ex;
        }
    }

    private void sleepWithJitter(Long productId) {
        long sleepMillis = ThreadLocalRandom.current().nextLong(20, 81);
        long startNanos = System.nanoTime();
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during inventory retry wait", interruptedException);
        } finally {
            metricsService.recordDuration(
                    "parallelcart.inventory.optimistic.retry.sleep.duration",
                    Tags.of("product_id", String.valueOf(productId)),
                    System.nanoTime() - startNanos);
        }
    }

    private void recordCheckoutPhase(String phase, long startedNanos) {
        metricsService.recordDuration(
                "parallelcart.checkout.phase.duration",
                Tags.of("phase", phase),
                System.nanoTime() - startedNanos);
    }

    private void recordAfterTransactionCompletion(String metricName, Tags tags, long startedNanos) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metricsService.recordDuration(metricName, tags, System.nanoTime() - startedNanos);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                metricsService.recordDuration(metricName, tags, System.nanoTime() - startedNanos);
            }
        });
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    private Product getProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
    }

    private Cart getOrCreateCart(User user) {
        return cartRepository.findByUser(user).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setUser(user);
            return cartRepository.save(cart);
        });
    }

    private CartResponse toCartResponse(Cart cart) {
        List<CartItemResponse> items = cartItemRepository.findByCart(cart).stream()
                .map(item -> new CartItemResponse(
                        item.getId(),
                        item.getProduct().getId(),
                        item.getProduct().getName(),
                        item.getQuantity()
                ))
                .toList();

        return new CartResponse(cart.getId(), cart.getUser().getId(), items);
    }
}
