package com.parallelcart.config;

import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Product;
import com.parallelcart.domain.model.User;
import com.parallelcart.domain.model.enums.UserRole;
import com.parallelcart.infra.repository.InventoryRepository;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.infra.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class DataSeeder {

    @Bean
    @Profile("local")
    @ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
    CommandLineRunner seedData(
            UserRepository userRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository
    ) {
        return args -> {
            if (userRepository.count() == 0) {
                User admin = new User();
                admin.setEmail("admin@parallelcart.local");
                admin.setPasswordHash("dev-admin-hash");
                admin.setRole(UserRole.ADMIN);

                User customer = new User();
                customer.setEmail("customer@parallelcart.local");
                customer.setPasswordHash("dev-customer-hash");
                customer.setRole(UserRole.CUSTOMER);

                userRepository.saveAll(List.of(admin, customer));
            }

            if (productRepository.count() == 0) {
                for (int i = 1; i <= 40; i++) {
                    Product product = new Product();
                    product.setSku("SKU-" + String.format("%04d", i));
                    product.setName("Sample Product " + i);
                    product.setDescription("Generated seed product " + i + " for load/stress tests");
                    product.setPrice(BigDecimal.valueOf(10 + i));
                    product.setActive(true);
                    Product saved = productRepository.save(product);

                    Inventory inventory = new Inventory();
                    inventory.setProduct(saved);
                    inventory.setAvailableQuantity(500);
                    inventoryRepository.save(inventory);
                }
            }
        };
    }
}
