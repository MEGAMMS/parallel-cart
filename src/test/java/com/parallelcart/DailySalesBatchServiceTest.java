package com.parallelcart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.domain.model.BatchJobCheckpoint;
import com.parallelcart.domain.model.DailySalesAggregate;
import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Product;
import com.parallelcart.domain.model.User;
import com.parallelcart.domain.model.enums.BatchJobStatus;
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
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "app.batch.daily-sales.chunk-size=2",
        "app.batch.daily-sales.zone-id=UTC",
        "app.checkout.max-concurrent=100"
})
@ActiveProfiles("test")
class DailySalesBatchServiceTest {

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
    void dailySalesBatchShouldProcessOrdersInChunks() {
        User buyer = createUser("batch-chunk@parallelcart.local");
        Product product = createProduct("SKU-BATCH-1", BigDecimal.valueOf(10));
        createInventory(product, 100);

        createOrdersForUser(buyer, product, 5);

        LocalDate businessDate = LocalDate.now(ZoneId.of("UTC"));
        DailySalesBatchExecutionResult result = dailySalesBatchService.processBusinessDate(businessDate);

        assertTrue(result.completed());
        assertEquals(3, result.chunksProcessed());
        assertEquals(5, result.recordsProcessed());
        assertEquals(5, result.aggregatedOrderCount());
        assertEquals(0, BigDecimal.valueOf(50).compareTo(result.aggregatedTotalAmount()));

        DailySalesAggregate aggregate = dailySalesAggregateRepository.findByBusinessDate(businessDate).orElseThrow();
        assertEquals(5L, aggregate.getOrderCount());
        assertEquals(0, BigDecimal.valueOf(50).compareTo(aggregate.getTotalAmount()));

        BatchJobCheckpoint checkpoint = batchJobCheckpointRepository
                .findByJobNameAndBusinessDate(DailySalesBatchService.DAILY_SALES_JOB_NAME, businessDate)
                .orElseThrow();
        assertEquals(BatchJobStatus.COMPLETED, checkpoint.getStatus());
        assertEquals(5L, checkpoint.getProcessedRecords());
    }

    @Test
    void dailySalesBatchShouldResumeFromCheckpoint() {
        User buyer = createUser("batch-resume@parallelcart.local");
        Product product = createProduct("SKU-BATCH-2", BigDecimal.valueOf(12));
        createInventory(product, 100);

        createOrdersForUser(buyer, product, 6);

        LocalDate businessDate = LocalDate.now(ZoneId.of("UTC"));
        DailySalesBatchExecutionResult firstRun = dailySalesBatchService.processBusinessDateWithLimit(businessDate, 1);
        assertFalse(firstRun.completed());
        assertEquals(1, firstRun.chunksProcessed());
        assertEquals(2, firstRun.recordsProcessed());

        BatchJobCheckpoint afterFirstRun = batchJobCheckpointRepository
                .findByJobNameAndBusinessDate(DailySalesBatchService.DAILY_SALES_JOB_NAME, businessDate)
                .orElseThrow();
        assertEquals(BatchJobStatus.RUNNING, afterFirstRun.getStatus());
        assertEquals(2L, afterFirstRun.getProcessedRecords());

        DailySalesBatchExecutionResult secondRun = dailySalesBatchService.processBusinessDate(businessDate);
        assertTrue(secondRun.completed());
        assertEquals(2, secondRun.chunksProcessed());
        assertEquals(4, secondRun.recordsProcessed());
        assertEquals(6, secondRun.aggregatedOrderCount());
        assertEquals(0, BigDecimal.valueOf(72).compareTo(secondRun.aggregatedTotalAmount()));

        BatchJobCheckpoint completedCheckpoint = batchJobCheckpointRepository
                .findByJobNameAndBusinessDate(DailySalesBatchService.DAILY_SALES_JOB_NAME, businessDate)
                .orElseThrow();
        assertEquals(BatchJobStatus.COMPLETED, completedCheckpoint.getStatus());
        assertEquals(6L, completedCheckpoint.getProcessedRecords());
    }

    private void createOrdersForUser(User user, Product product, int count) {
        for (int i = 0; i < count; i++) {
            cartService.addItem(user.getId(), new CartItemRequest(product.getId(), 1));
            cartService.checkout(user.getId(), "batch-order-" + i + "-" + System.nanoTime());
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
