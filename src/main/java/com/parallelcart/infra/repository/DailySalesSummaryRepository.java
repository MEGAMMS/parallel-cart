package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.DailySalesSummary;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailySalesSummaryRepository extends JpaRepository<DailySalesSummary, Long> {
    Optional<DailySalesSummary> findBySalesDate(LocalDate salesDate);
}
