package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.enums.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    @Query("select distinct o from Order o where o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    @EntityGraph(attributePaths = {"user", "items", "items.product"})
    @Query("select distinct o from Order o where o.user.id = :userId order by o.id desc")
    List<Order> findByUserIdWithItemsOrderByIdDesc(@Param("userId") Long userId);

    List<Order> findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndIdGreaterThanOrderByIdAsc(
            OrderStatus status,
            Instant fromInclusive,
            Instant toExclusive,
            Long lastProcessedOrderId,
            Pageable pageable);
}
