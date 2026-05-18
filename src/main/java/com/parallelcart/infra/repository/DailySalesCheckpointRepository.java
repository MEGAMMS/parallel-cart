package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.DailySalesCheckpoint;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailySalesCheckpointRepository extends JpaRepository<DailySalesCheckpoint, Long> {
    Optional<DailySalesCheckpoint> findBySalesDate(LocalDate salesDate);
}
