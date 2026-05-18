package com.parallelcart.infra.repository;

import com.parallelcart.api.dto.ProductResponse;
import com.parallelcart.domain.model.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);

    @Query("""
            select new com.parallelcart.api.dto.ProductResponse(
                p.id, p.sku, p.name, p.description, p.price, p.active
            )
            from Product p
            order by p.id asc
            """)
    List<ProductResponse> findAllResponses();

    @Query("""
            select new com.parallelcart.api.dto.ProductResponse(
                p.id, p.sku, p.name, p.description, p.price, p.active
            )
            from Product p
            where p.id = :id
            """)
    Optional<ProductResponse> findResponseById(Long id);
}
