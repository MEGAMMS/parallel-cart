package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Inventory;
import com.parallelcart.domain.model.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProduct(Product product);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.product = :product")
    Optional<Inventory> findByProductForUpdate(Product product);
}
