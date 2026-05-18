package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.enums.OrderStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    Page<Order> findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(
            OrderStatus status,
            Instant fromInclusive,
            Instant toExclusive,
            Pageable pageable);
}
