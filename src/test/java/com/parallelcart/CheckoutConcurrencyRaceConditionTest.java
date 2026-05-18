package com.parallelcart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.parallelcart.api.dto.CartItemRequest;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.checkout.max-concurrent=100")
@ActiveProfiles("test")
class CheckoutConcurrencyRaceConditionTest {

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

    @BeforeEach
    void cleanup() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void parallelCheckoutAgainstSameInventoryShouldNotOversell() throws Exception {
        int initialStock = 5;
        int buyerCount = 12;
        int quantityPerBuyer = 1;

        Product product = createProduct("SKU-RACE", BigDecimal.valueOf(15));
        createInventory(product, initialStock);

        List<User> buyers = IntStream.range(0, buyerCount)
                .mapToObj(i -> createUser("race-buyer-" + i + "@parallelcart.local"))
                .toList();

        for (User buyer : buyers) {
            cartService.addItem(buyer.getId(), new CartItemRequest(product.getId(), quantityPerBuyer));
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyerCount);
        CountDownLatch ready = new CountDownLatch(buyerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyerCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < buyerCount; i++) {
            User buyer = buyers.get(i);
            String idempotencyKey = "race-checkout-" + i + "-" + UUID.randomUUID();
            pool.submit(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS), "start barrier timed out");
                    cartService.checkout(buyer.getId(), idempotencyKey);
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                    failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers were not ready in time");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "pool did not terminate in time");

        Inventory inventory = inventoryRepository.findByProduct(product).orElseThrow();

        int successes = successCount.get();
        int failuresCount = failureCount.get();

        assertTrue(failuresCount > 0, "some checkouts must fail when demand exceeds stock");
        assertEquals(buyerCount, successes + failuresCount, "every worker must produce a result");
        assertTrue(successes <= initialStock, "successful checkouts cannot exceed available stock");
        assertEquals(initialStock - (successes * quantityPerBuyer), inventory.getAvailableQuantity());
        assertTrue(inventory.getAvailableQuantity() >= 0, "inventory must never go negative");
        assertEquals(successes, orderRepository.count(), "one order should exist per successful checkout");
        assertEquals(successes, paymentRepository.count(), "one payment should exist per successful checkout");

        System.out.printf(
                "Race test summary: initialStock=%d, buyers=%d, successes=%d, failures=%d, finalInventory=%d%n",
                initialStock,
                buyerCount,
                successes,
                failuresCount,
                inventory.getAvailableQuantity());

        if (!failures.isEmpty()) {
            System.out.println("Race test failures (expected under contention): " + failures);
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
}
