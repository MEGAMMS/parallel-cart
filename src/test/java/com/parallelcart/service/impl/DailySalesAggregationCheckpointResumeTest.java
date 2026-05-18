package com.parallelcart.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.parallelcart.domain.model.DailySalesCheckpoint;
import com.parallelcart.domain.model.DailySalesSummary;
import com.parallelcart.domain.model.enums.BatchRunStatus;
import com.parallelcart.infra.repository.DailySalesCheckpointRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.batch.daily-sales.chunk-size=2",
        "app.batch.daily-sales.max-chunks-per-run=1"
})
@Transactional
class DailySalesAggregationCheckpointResumeTest {

    @Autowired
    private DailySalesAggregationService dailySalesAggregationService;

    @Autowired
    private DailySalesSummaryRepository dailySalesSummaryRepository;

    @Autowired
    private DailySalesCheckpointRepository dailySalesCheckpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update(
                "insert into users (email, password_hash, role, created_at) values (?, ?, ?, CURRENT_TIMESTAMP)",
                "batch-checkpoint-" + UUID.randomUUID() + "@example.com",
                "hash",
                "CUSTOMER");
        userId = jdbcTemplate.queryForObject("select max(id) from users", Long.class);
    }

    @Test
    void aggregate_shouldResumeFromCheckpointAcrossRuns() {
        LocalDate salesDate = LocalDate.now().minusDays(1);

        insertOrder(new BigDecimal("10.00"), salesDate.atTime(8, 0));
        insertOrder(new BigDecimal("20.00"), salesDate.atTime(9, 0));
        insertOrder(new BigDecimal("30.00"), salesDate.atTime(10, 0));
        insertOrder(new BigDecimal("40.00"), salesDate.atTime(11, 0));

        dailySalesAggregationService.aggregate(salesDate);

        DailySalesCheckpoint firstRun = dailySalesCheckpointRepository.findBySalesDate(salesDate).orElseThrow();
        assertThat(firstRun.getStatus()).isEqualTo(BatchRunStatus.IN_PROGRESS);
        assertThat(firstRun.getPaidOrderCount()).isEqualTo(2L);
        assertThat(firstRun.getProcessedChunks()).isEqualTo(1L);
        assertThat(dailySalesSummaryRepository.findBySalesDate(salesDate)).isEmpty();

        dailySalesAggregationService.aggregate(salesDate);
        dailySalesAggregationService.aggregate(salesDate);

        DailySalesCheckpoint finalCheckpoint = dailySalesCheckpointRepository.findBySalesDate(salesDate).orElseThrow();
        DailySalesSummary summary = dailySalesSummaryRepository.findBySalesDate(salesDate).orElseThrow();

        assertThat(finalCheckpoint.getStatus()).isEqualTo(BatchRunStatus.COMPLETED);
        assertThat(summary.getPaidOrderCount()).isEqualTo(4L);
        assertThat(summary.getTotalRevenue()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(summary.getProcessedChunks()).isEqualTo(2L);
    }

    private void insertOrder(BigDecimal totalAmount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                """
                insert into orders (user_id, status, total_amount, idempotency_key, created_at)
                values (?, ?, ?, ?, ?)
                """,
                userId,
                "PAID",
                totalAmount,
                "idem-" + UUID.randomUUID(),
                createdAt);
    }
}
