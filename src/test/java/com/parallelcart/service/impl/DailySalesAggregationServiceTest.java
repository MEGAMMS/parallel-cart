package com.parallelcart.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.parallelcart.domain.model.DailySalesSummary;
import com.parallelcart.infra.repository.DailySalesSummaryRepository;
import com.parallelcart.service.DailySalesAggregationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DailySalesAggregationServiceTest {

    @Autowired
    private DailySalesAggregationService dailySalesAggregationService;

    @Autowired
    private DailySalesSummaryRepository dailySalesSummaryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, created_at) values (?, ?, ?, CURRENT_TIMESTAMP)",
                "batch-test-" + UUID.randomUUID() + "@example.com",
                "hash",
                "CUSTOMER");
        userId = jdbcTemplate.queryForObject("select max(id) from users", Long.class);
    }

    @Test
    void aggregate_shouldSummarizeOnlyPaidOrdersInChunks() {
        LocalDate salesDate = LocalDate.now().minusDays(1);

        insertOrder("PAID", new BigDecimal("100.00"), salesDate.atTime(9, 0));
        insertOrder("PAID", new BigDecimal("25.50"), salesDate.atTime(12, 30));
        insertOrder("CANCELLED", new BigDecimal("900.00"), salesDate.atTime(14, 0));
        insertOrder("PAID", new BigDecimal("10.00"), salesDate.minusDays(1).atTime(8, 0));

        dailySalesAggregationService.aggregate(salesDate);

        DailySalesSummary summary = dailySalesSummaryRepository.findBySalesDate(salesDate).orElseThrow();
        assertThat(summary.getPaidOrderCount()).isEqualTo(2L);
        assertThat(summary.getTotalRevenue()).isEqualByComparingTo(new BigDecimal("125.50"));
        assertThat(summary.getProcessedChunks()).isEqualTo(1L);
    }

    @Test
    void aggregate_shouldUpdateExistingSummaryWhenRerun() {
        LocalDate salesDate = LocalDate.now().minusDays(1);

        insertOrder("PAID", new BigDecimal("40.00"), salesDate.atTime(10, 0));
        dailySalesAggregationService.aggregate(salesDate);

        insertOrder("PAID", new BigDecimal("15.00"), salesDate.atTime(18, 15));
        dailySalesAggregationService.aggregate(salesDate);

        DailySalesSummary summary = dailySalesSummaryRepository.findBySalesDate(salesDate).orElseThrow();
        assertThat(summary.getPaidOrderCount()).isEqualTo(2L);
        assertThat(summary.getTotalRevenue()).isEqualByComparingTo(new BigDecimal("55.00"));
    }

    private void insertOrder(String status, BigDecimal totalAmount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                insert into orders (user_id, status, total_amount, idempotency_key, created_at)
                values (?, ?, ?, ?, ?)
                """,
                userId,
                status,
                totalAmount,
                "idem-" + UUID.randomUUID(),
                createdAt);
    }
}
