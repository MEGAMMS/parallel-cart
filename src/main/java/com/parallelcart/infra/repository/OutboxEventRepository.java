package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.OutboxEvent;
import com.parallelcart.domain.model.enums.OutboxStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
