package com.parallelcart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.domain.model.BatchJobCheckpoint;
import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.Product;
import com.parallelcart.domain.model.User;
import com.parallelcart.domain.model.enums.BatchJobStatus;
import com.parallelcart.domain.model.enums.OrderStatus;
import com.parallelcart.infra.repository.BatchJobCheckpointRepository;
import com.parallelcart.infra.repository.CartItemRepository;
import com.parallelcart.infra.repository.CartRepository;
import com.parallelcart.infra.repository.DailySalesAggregateRepository;
import com.parallelcart.infra.repository.InventoryRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.infra.repository.OutboxEventRepository;
import com.parallelcart.infra.repository.PaymentRepository;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.infra.repository.UserRepository;
import com.parallelcart.service.CartService;
import com.parallelcart.service.DailySalesBatchExecutionResult;
import com.parallelcart.service.impl.DailySalesBatchService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "app.batch.daily-sales.chunk-size=5",
        "app.batch.daily-sales.zone-id=UTC",
        "app.checkout.max-concurrent=100"
})
@ActiveProfiles("test")
class DailySalesBatchBeforeAfterComparisonTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private DailySalesBatchService dailySalesBatchService;

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
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private DailySalesAggregateRepository dailySalesAggregateRepository;

    @Autowired
    private BatchJobCheckpointRepository batchJobCheckpointRepository;

    @BeforeEach
    void cleanup() {
        outboxEventRepository.deleteAll();
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();
        dailySalesAggregateRepository.deleteAll();
        batchJobCheckpointRepository.deleteAll();
    }

    @Test
    void shouldCompareSinglePassBaselineWithChunkedResumeFlow() {
        int orderCount = 23;
        User buyer = createUser("batch-compare@parallelcart.local");
        Product product = createProduct("SKU-BATCH-COMPARE", BigDecimal.valueOf(14));
        createInventory(product, 500);
        createOrdersForUser(buyer, product, orderCount);

        LocalDate businessDate = LocalDate.now(ZoneId.of("UTC"));
        Instant fromInclusive = businessDate.atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant toExclusive = businessDate.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();

        long baselineStart = System.nanoTime();
        List<Order> allOrders = orderRepository.findDailySalesChunk(
                OrderStatus.PAID,
                fromInclusive,
                toExclusive,
                0L,
                PageRequest.of(0, 10_000, Sort.by(Sort.Direction.ASC, "id")));
        long baselineDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - baselineStart);

        long baselineRecords = allOrders.size();
        BigDecimal baselineTotal = allOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(orderCount, baselineRecords);

        long chunkedStart = System.nanoTime();
        DailySalesBatchExecutionResult firstRun = dailySalesBatchService.processBusinessDateWithLimit(businessDate, 1);
        BatchJobCheckpoint afterFirstRun = batchJobCheckpointRepository
                .findByJobNameAndBusinessDate(DailySalesBatchService.DAILY_SALES_JOB_NAME, businessDate)
                .orElseThrow();

        DailySalesBatchExecutionResult resumedRun = dailySalesBatchService.processBusinessDate(businessDate);
        BatchJobCheckpoint completedCheckpoint = batchJobCheckpointRepository
                .findByJobNameAndBusinessDate(DailySalesBatchService.DAILY_SALES_JOB_NAME, businessDate)
                .orElseThrow();
        long chunkedDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - chunkedStart);

        assertFalse(firstRun.completed());
        assertEquals(BatchJobStatus.RUNNING, afterFirstRun.getStatus());
        assertTrue(resumedRun.completed());
        assertEquals(BatchJobStatus.COMPLETED, completedCheckpoint.getStatus());
        assertEquals(orderCount, resumedRun.aggregatedOrderCount());
        assertEquals(0, baselineTotal.compareTo(resumedRun.aggregatedTotalAmount()));

        System.out.printf(
                "Batch baseline summary: mode=single-pass, records=%d, chunkSize=ALL, checkpoint=NONE, durationMs=%d%n",
                baselineRecords,
                baselineDurationMs);
        System.out.printf(
                "Batch chunked summary: mode=chunked-resume, records=%d, chunkSize=%d, firstRunCompleted=%s, firstRunProcessed=%d, finalCheckpoint=%s, durationMs=%d%n",
                resumedRun.aggregatedOrderCount(),
                5,
                firstRun.completed(),
                firstRun.recordsProcessed(),
                completedCheckpoint.getStatus(),
                chunkedDurationMs);
    }

    private void createOrdersForUser(User user, Product product, int count) {
        for (int i = 0; i < count; i++) {
            cartService.addItem(user.getId(), new CartItemRequest(product.getId(), 1));
            cartService.checkout(user.getId(), "batch-compare-" + i + "-" + System.nanoTime());
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
