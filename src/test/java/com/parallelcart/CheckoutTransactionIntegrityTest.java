package com.parallelcart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.parallelcart.api.dto.CartItemRequest;
import com.parallelcart.api.dto.CheckoutResponse;
import com.parallelcart.domain.model.Cart;
import com.parallelcart.domain.model.CartItem;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CheckoutTransactionIntegrityTest {

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
    void checkoutShouldCommitOrderPaymentAndInventoryTogether() {
        User user = createUser("success@parallelcart.local");
        Product product = createProduct("SKU-SUCCESS", BigDecimal.valueOf(50));
        createInventory(product, 10);

        cartService.addItem(user.getId(), new CartItemRequest(product.getId(), 2));
        CheckoutResponse response = cartService.checkout(user.getId());

        assertEquals(1, orderRepository.count());
        assertEquals(1, paymentRepository.count());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(response.totalAmount()));
        assertEquals(8, inventoryRepository.findByProduct(product).orElseThrow().getAvailableQuantity());
        Cart cart = cartRepository.findByUser(user).orElseThrow();
        assertEquals(0, cartItemRepository.findByCart(cart).size());
    }

    @Test
    void checkoutShouldRollbackAllWhenFailureOccursMidFlow() {
        User user = createUser("rollback@parallelcart.local");
        Product product = createProduct("SKU-ROLLBACK", BigDecimal.ZERO);
        createInventory(product, 10);

        cartService.addItem(user.getId(), new CartItemRequest(product.getId(), 2));

        assertThrows(IllegalStateException.class, () -> cartService.checkout(user.getId()));

        assertEquals(0, orderRepository.count());
        assertEquals(0, paymentRepository.count());
        assertEquals(10, inventoryRepository.findByProduct(product).orElseThrow().getAvailableQuantity());
        Cart cart = cartRepository.findByUser(user).orElseThrow();
        assertEquals(1, cartItemRepository.findByCart(cart).size());
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
