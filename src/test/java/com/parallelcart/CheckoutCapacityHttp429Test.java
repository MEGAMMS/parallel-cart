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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.checkout.max-concurrent=1")
@ActiveProfiles("test")
class CheckoutCapacityHttp429Test {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

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
    void saturatedCheckoutShouldReturnHttp429() throws Exception {
        int initialStock = 300;
        int buyerCount = 24;

        Product product = createProduct("SKU-CAPACITY-HTTP", BigDecimal.valueOf(30));
        createInventory(product, initialStock);

        List<User> buyers = IntStream.range(0, buyerCount)
                .mapToObj(i -> createUser("capacity-http-buyer-" + i + "@parallelcart.local"))
                .toList();

        for (User buyer : buyers) {
            cartService.addItem(buyer.getId(), new CartItemRequest(product.getId(), 1));
        }

        String baseUrl = "http://localhost:" + port;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ExecutorService pool = Executors.newFixedThreadPool(buyerCount);
        CountDownLatch ready = new CountDownLatch(buyerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(buyerCount);

        AtomicInteger status200 = new AtomicInteger();
        AtomicInteger status429 = new AtomicInteger();
        AtomicInteger statusOther = new AtomicInteger();
        AtomicInteger transportFailures = new AtomicInteger();

        for (int i = 0; i < buyerCount; i++) {
            User buyer = buyers.get(i);
            pool.submit(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS), "start barrier timeout");

                    String body = "{\"idempotencyKey\":\"capacity-http-" + UUID.randomUUID() + "\"}";
                    HttpEntity<String> entity = new HttpEntity<>(body, headers);
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            baseUrl + "/api/carts/" + buyer.getId() + "/checkout",
                            entity,
                            String.class);

                    int code = response.getStatusCode().value();
                    if (code == 200) {
                        status200.incrementAndGet();
                    } else if (code == 429) {
                        status429.incrementAndGet();
                    } else {
                        statusOther.incrementAndGet();
                    }
                } catch (Exception ex) {
                    transportFailures.incrementAndGet();
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

        int ok = status200.get();
        int tooMany = status429.get();
        int other = statusOther.get();
        int failed = transportFailures.get();

        assertEquals(0, failed, "transport failures are not expected");
        assertTrue(ok > 0, "at least one checkout should succeed");
        assertTrue(tooMany > 0, "at least one checkout should be rejected with HTTP 429");
        assertEquals(0, other, "unexpected HTTP status codes appeared");
        assertEquals(buyerCount, ok + tooMany + other + failed, "every request must have one outcome");

        System.out.printf(
                "Capacity HTTP summary: buyers=%d, status200=%d, status429=%d, otherStatus=%d%n",
                buyerCount,
                ok,
                tooMany,
                other);
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
