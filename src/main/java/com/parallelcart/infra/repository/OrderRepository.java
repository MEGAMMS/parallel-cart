package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.enums.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @Query("""
            select o
            from Order o
            where o.status = :status
              and o.createdAt >= :fromInclusive
              and o.createdAt < :toExclusive
              and o.id > :lastProcessedIdExclusive
            order by o.id asc
            """)
    List<Order> findDailySalesChunk(
            @Param("status") OrderStatus status,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            @Param("lastProcessedIdExclusive") Long lastProcessedIdExclusive,
            Pageable pageable
    );
}
