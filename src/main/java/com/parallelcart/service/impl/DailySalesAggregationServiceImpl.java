package com.parallelcart.service.impl;

import com.parallelcart.domain.model.DailySalesCheckpoint;
import com.parallelcart.domain.model.DailySalesSummary;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.enums.BatchRunStatus;
import com.parallelcart.domain.model.enums.OrderStatus;
import com.parallelcart.infra.repository.DailySalesCheckpointRepository;
import com.parallelcart.infra.repository.DailySalesSummaryRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.service.DailySalesAggregationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailySalesAggregationServiceImpl implements DailySalesAggregationService {

    private final OrderRepository orderRepository;
    private final DailySalesSummaryRepository dailySalesSummaryRepository;
    private final DailySalesCheckpointRepository dailySalesCheckpointRepository;
    private final int chunkSize;
    private final int maxChunksPerRun;

    public DailySalesAggregationServiceImpl(
            OrderRepository orderRepository,
            DailySalesSummaryRepository dailySalesSummaryRepository,
            DailySalesCheckpointRepository dailySalesCheckpointRepository,
            @Value("${app.batch.daily-sales.chunk-size:500}") int chunkSize,
            @Value("${app.batch.daily-sales.max-chunks-per-run:0}") int maxChunksPerRun) {
        this.orderRepository = orderRepository;
        this.dailySalesSummaryRepository = dailySalesSummaryRepository;
        this.dailySalesCheckpointRepository = dailySalesCheckpointRepository;
        this.chunkSize = chunkSize;
        this.maxChunksPerRun = maxChunksPerRun;
    }

    @Override
    @Transactional
    public void aggregate(LocalDate salesDate) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = salesDate.atStartOfDay(zone).toInstant();
        Instant end = salesDate.plusDays(1).atStartOfDay(zone).toInstant();

        DailySalesCheckpoint checkpoint = dailySalesCheckpointRepository.findBySalesDate(salesDate)
                .orElseGet(() -> newCheckpoint(salesDate));

        checkpoint.setStatus(BatchRunStatus.IN_PROGRESS);
        checkpoint.setUpdatedAt(Instant.now());
        dailySalesCheckpointRepository.save(checkpoint);

        int processedInRun = 0;
        while (true) {
            List<Order> orders = loadChunk(start, end, checkpoint.getLastProcessedOrderId());
            if (orders.isEmpty()) {
                finalizeSummary(salesDate, checkpoint);
                return;
            }

            applyChunk(checkpoint, orders);
            checkpoint.setUpdatedAt(Instant.now());
            dailySalesCheckpointRepository.save(checkpoint);

            processedInRun++;
            if (maxChunksPerRun > 0 && processedInRun >= maxChunksPerRun) {
                return;
            }
        }
    }

    private DailySalesCheckpoint newCheckpoint(LocalDate salesDate) {
        DailySalesCheckpoint checkpoint = new DailySalesCheckpoint();
        checkpoint.setSalesDate(salesDate);
        checkpoint.setStatus(BatchRunStatus.IN_PROGRESS);
        return checkpoint;
    }

    private List<Order> loadChunk(Instant start, Instant end, Long lastProcessedOrderId) {
        return orderRepository.findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanAndIdGreaterThanOrderByIdAsc(
                OrderStatus.PAID,
                start,
                end,
                lastProcessedOrderId,
                PageRequest.of(0, chunkSize));
    }

    private void applyChunk(DailySalesCheckpoint checkpoint, List<Order> orders) {
        for (Order order : orders) {
            checkpoint.setLastProcessedOrderId(order.getId());
            checkpoint.setPaidOrderCount(checkpoint.getPaidOrderCount() + 1);
            checkpoint.setTotalRevenue(checkpoint.getTotalRevenue().add(order.getTotalAmount()));
        }
        checkpoint.setProcessedChunks(checkpoint.getProcessedChunks() + 1);
    }

    private void finalizeSummary(LocalDate salesDate, DailySalesCheckpoint checkpoint) {
        DailySalesSummary summary = dailySalesSummaryRepository.findBySalesDate(salesDate)
                .orElseGet(DailySalesSummary::new);
        summary.setSalesDate(salesDate);
        summary.setPaidOrderCount(checkpoint.getPaidOrderCount());
        summary.setTotalRevenue(checkpoint.getTotalRevenue());
        summary.setProcessedChunks(checkpoint.getProcessedChunks());
        summary.setAggregatedAt(Instant.now());

        dailySalesSummaryRepository.save(summary);
        checkpoint.setStatus(BatchRunStatus.COMPLETED);
        checkpoint.setUpdatedAt(Instant.now());
        dailySalesCheckpointRepository.save(checkpoint);
    }
}
