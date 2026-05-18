package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.enums.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    List<Order> findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndIdGreaterThanOrderByIdAsc(
            OrderStatus status,
            Instant fromInclusive,
            Instant toExclusive,
            Long lastProcessedOrderId,
            Pageable pageable);
}
