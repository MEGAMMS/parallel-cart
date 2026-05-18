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
import com.parallelcart.service.CheckoutCapacityExceededException;
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
class CheckoutCapacityBypassBaselineTest {

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
    void highCapacitySettingShouldAvoidCapacityRejections() throws Exception {
        int buyerCount = 10;

        List<User> buyers = IntStream.range(0, buyerCount)
                .mapToObj(i -> createUser("capacity-open-buyer-" + i + "@parallelcart.local"))
                .toList();

        for (int i = 0; i < buyerCount; i++) {
            Product dedicatedProduct = createProduct("SKU-CAPACITY-BYPASS-" + i, BigDecimal.valueOf(25 + i));
            createInventory(dedicatedProduct, 1);
            cartService.addItem(buyers.get(i).getId(), new CartItemRequest(dedicatedProduct.getId(), 1));
        }

        ExecutorService pool = Executors.newFixedThreadPool(buyerCount);
        CountDownLatch ready = new CountDownLatch(buyerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyerCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicInteger unexpectedFailureCount = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpectedFailures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < buyerCount; i++) {
            User buyer = buyers.get(i);
            String idempotencyKey = "capacity-open-" + i + "-" + UUID.randomUUID();
            pool.submit(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS), "start barrier timeout");
                    cartService.checkout(buyer.getId(), idempotencyKey);
                    successCount.incrementAndGet();
                } catch (CheckoutCapacityExceededException ex) {
                    rejectedCount.incrementAndGet();
                } catch (Exception ex) {
                    unexpectedFailureCount.incrementAndGet();
                    unexpectedFailures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "workers not ready");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");

        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "pool did not terminate");

        int successes = successCount.get();
        int rejections = rejectedCount.get();
        int unexpectedFailuresCount = unexpectedFailureCount.get();
        int remainingInventory = inventoryRepository.findAll().stream()
                .mapToInt(Inventory::getAvailableQuantity)
                .sum();

        assertEquals(0, rejections, "capacity rejections are not expected with high limit");
        assertEquals(0, unexpectedFailuresCount, "unexpected failures: " + unexpectedFailures);
        assertEquals(buyerCount, successes, "all buyers should checkout successfully");
        assertEquals(0, remainingInventory, "all dedicated inventory entries should be consumed");
        assertEquals(buyerCount, orderRepository.count());
        assertEquals(buyerCount, paymentRepository.count());

        System.out.printf(
                "Capacity baseline summary: buyers=%d, successes=%d, rejections=%d, finalInventory=%d%n",
                buyerCount,
                successes,
                rejections,
                remainingInventory);
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
