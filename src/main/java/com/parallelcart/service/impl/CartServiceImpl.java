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
import com.parallelcart.infra.repository.CartItemRepository;
import com.parallelcart.infra.repository.CartRepository;
import com.parallelcart.infra.repository.InventoryRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.infra.repository.PaymentRepository;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.infra.repository.UserRepository;
import com.parallelcart.service.CartService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public CartServiceImpl(
            UserRepository userRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            CartRepository cartRepository,
            CartItemRepository cartItemRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository
    ) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
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
        return toCartResponse(cart);
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        return toCartResponse(getOrCreateCart(getUser(userId)));
    }

    @Override
    @Transactional
    public CheckoutResponse checkout(Long userId) {
        User user = getUser(userId);
        Cart cart = getOrCreateCart(user);
        List<CartItem> items = cartItemRepository.findByCart(cart);

        if (items.isEmpty()) {
            throw new IllegalStateException("Cart is empty");
        }

        BigDecimal total = BigDecimal.ZERO;
        Order order = new Order();
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);

        for (CartItem cartItem : items) {
            reserveInventoryWithRetry(cartItem.getProduct(), cartItem.getQuantity());

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(cartItem.getProduct());
            orderItem.setQuantity(cartItem.getQuantity());
            orderItem.setUnitPrice(cartItem.getProduct().getPrice());
            order.getItems().add(orderItem);

            total = total.add(cartItem.getProduct().getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }

        if (total.signum() <= 0) {
            throw new IllegalStateException("Invalid checkout total");
        }

        order.setTotalAmount(total);
        order.setStatus(OrderStatus.PAID);
        order = orderRepository.save(order);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(total);
        payment.setExternalRef("PAY-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID());
        payment.setStatus(PaymentStatus.CAPTURED);
        payment = paymentRepository.save(payment);

        cartItemRepository.deleteAll(items);
        cart.touch();
        cartRepository.save(cart);

        return new CheckoutResponse(order.getId(), payment.getId(), total, order.getStatus().name());
    }

    private void reserveInventoryWithRetry(Product product, Integer requestedQuantity) {
        ObjectOptimisticLockingFailureException lastConflict = null;
        for (int attempt = 1; attempt <= INVENTORY_RETRY_MAX_ATTEMPTS; attempt++) {
            Inventory inventory = inventoryRepository.findByProduct(product)
                    .orElseThrow(() -> new IllegalStateException("Inventory missing for product " + product.getId()));

            if (inventory.getAvailableQuantity() < requestedQuantity) {
                throw new IllegalStateException("Insufficient inventory for product " + product.getId());
            }

            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - requestedQuantity);
            try {
                inventoryRepository.saveAndFlush(inventory);
                return;
            } catch (ObjectOptimisticLockingFailureException ex) {
                lastConflict = ex;
                sleepWithJitter();
            }
        }

        reserveInventoryWithPessimisticLock(product, requestedQuantity, lastConflict);
    }

    private void reserveInventoryWithPessimisticLock(
            Product product,
            Integer requestedQuantity,
            ObjectOptimisticLockingFailureException lastConflict
    ) {
        Inventory lockedInventory = inventoryRepository.findByProductForUpdate(product)
                .orElseThrow(() -> new IllegalStateException("Inventory missing for product " + product.getId()));

        if (lockedInventory.getAvailableQuantity() < requestedQuantity) {
            throw new IllegalStateException("Insufficient inventory for product " + product.getId());
        }

        lockedInventory.setAvailableQuantity(lockedInventory.getAvailableQuantity() - requestedQuantity);
        try {
            inventoryRepository.saveAndFlush(lockedInventory);
        } catch (ObjectOptimisticLockingFailureException ex) {
            if (lastConflict != null) {
                ex.addSuppressed(lastConflict);
            }
            throw ex;
        }
    }

    private void sleepWithJitter() {
        long sleepMillis = ThreadLocalRandom.current().nextLong(20, 81);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during inventory retry wait", interruptedException);
        }
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
