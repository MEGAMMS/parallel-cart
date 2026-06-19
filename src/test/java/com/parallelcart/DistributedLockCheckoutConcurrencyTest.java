package com.parallelcart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.parallelcart.FakeRedissonLockConfig.FakeRedissonLockControl;
import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.api.dto.CheckoutResponse;
import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Product;
import com.parallelcart.domain.model.User;
import com.parallelcart.infra.repository.CartItemRepository;
import com.parallelcart.infra.repository.CartRepository;
import com.parallelcart.infra.repository.InventoryRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.infra.repository.PaymentRepository;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.infra.repository.UserRepository;
import com.parallelcart.service.CartService;
import com.parallelcart.service.DistributedLockUnavailableException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(FakeRedissonLockConfig.class)
class DistributedLockCheckoutConcurrencyTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private FakeRedissonLockControl lockControl;

    @BeforeEach
    void cleanup() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        lockControl.reset();
    }

    @AfterEach
    void resetLocks() {
        lockControl.reset();
    }

    @Test
    void inventoryLockSerializesConcurrentCheckoutsForSameProduct() throws Exception {
        Product product = createProduct("SKU-INVENTORY-LOCK", BigDecimal.valueOf(25));
        createInventory(product, 2);
        User firstUser = createUser("inventory-lock-1@parallelcart.local");
        User secondUser = createUser("inventory-lock-2@parallelcart.local");
        cartService.addItem(firstUser.getId(), new CartItemRequest(product.getId(), 1));
        cartService.addItem(secondUser.getId(), new CartItemRequest(product.getId(), 1));

        String inventoryLockKey = "inventory:product:" + product.getId();
        lockControl.delayAfterAcquire("inventory:product:", Duration.ofMillis(150));

        List<CheckoutAttempt> attempts = runConcurrently(List.of(
                () -> cartService.checkout(firstUser.getId(), "idem-inventory-1"),
                () -> cartService.checkout(secondUser.getId(), "idem-inventory-2")
        ));

        assertEquals(2, attempts.stream().filter(CheckoutAttempt::succeeded).count());
        assertEquals(2, orderRepository.count());
        assertEquals(2, paymentRepository.count());
        assertEquals(0, inventoryRepository.findByProduct(product).orElseThrow().getAvailableQuantity());
        assertEquals(2, lockControl.acquisitionCount(inventoryLockKey));
        assertEquals(0, lockControl.failureCount(inventoryLockKey));
    }

    @Test
    void idempotencyLockAllowsOnlyOneConcurrentCheckoutForSameKey() throws Exception {
        User user = createUser("idempotency-lock@parallelcart.local");
        Product product = createProduct("SKU-IDEMPOTENCY-LOCK", BigDecimal.valueOf(30));
        createInventory(product, 5);
        cartService.addItem(user.getId(), new CartItemRequest(product.getId(), 1));

        String idempotencyKey = "idem-lock-shared";
        String idempotencyLockKey = "checkout:idempotency:" + idempotencyKey;
        lockControl.delayAfterAcquire("checkout:idempotency:", Duration.ofMillis(300));

        List<CheckoutAttempt> attempts = runConcurrently(List.of(
                () -> cartService.checkout(user.getId(), idempotencyKey),
                () -> cartService.checkout(user.getId(), idempotencyKey),
                () -> cartService.checkout(user.getId(), idempotencyKey)
        ));

        List<CheckoutAttempt> successes = attempts.stream().filter(CheckoutAttempt::succeeded).toList();
        List<CheckoutAttempt> failures = attempts.stream().filter(attempt -> !attempt.succeeded()).toList();

        assertEquals(1, successes.size());
        assertNotNull(successes.get(0).response());
        assertEquals(2, failures.size());
        failures.forEach(attempt ->
                assertInstanceOf(DistributedLockUnavailableException.class, attempt.error()));
        assertEquals(1, orderRepository.count());
        assertEquals(1, paymentRepository.count());
        assertEquals(4, inventoryRepository.findByProduct(product).orElseThrow().getAvailableQuantity());
        assertEquals(1, lockControl.acquisitionCount(idempotencyLockKey));
        assertEquals(2, lockControl.failureCount(idempotencyLockKey));
    }

    private List<CheckoutAttempt> runConcurrently(List<Callable<CheckoutResponse>> checkoutCalls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(checkoutCalls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CheckoutAttempt>> futures = checkoutCalls.stream()
                    .map(checkoutCall -> executor.submit(() -> {
                        start.await();
                        try {
                            return new CheckoutAttempt(checkoutCall.call(), null);
                        } catch (Throwable ex) {
                            return new CheckoutAttempt(null, ex);
                        }
                    }))
                    .toList();

            start.countDown();
            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (Exception ex) {
                            throw new IllegalStateException("Concurrent checkout did not complete", ex);
                        }
                    })
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private User createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("hash");
        return userRepository.save(user);
    }

    private Product createProduct(String sku, BigDecimal price) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(sku + "-name");
        product.setDescription("test product");
        product.setPrice(price);
        product.setActive(true);
        return productRepository.save(product);
    }

    private void createInventory(Product product, int quantity) {
        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setAvailableQuantity(quantity);
        inventoryRepository.save(inventory);
    }

    private record CheckoutAttempt(CheckoutResponse response, Throwable error) {
        private boolean succeeded() {
            return error == null;
        }
    }
}
