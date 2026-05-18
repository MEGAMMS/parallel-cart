package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.DailySalesAggregate;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailySalesAggregateRepository extends JpaRepository<DailySalesAggregate, Long> {
    Optional<DailySalesAggregate> findByBusinessDate(LocalDate businessDate);
}
