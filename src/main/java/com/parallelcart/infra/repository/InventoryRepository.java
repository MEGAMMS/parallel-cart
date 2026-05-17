package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProduct(Product product);
}
