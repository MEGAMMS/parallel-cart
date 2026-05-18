package com.parallelcart.service.impl;

import com.parallelcart.domain.model.DailySalesSummary;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.enums.OrderStatus;
import com.parallelcart.infra.repository.DailySalesSummaryRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.service.DailySalesAggregationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailySalesAggregationServiceImpl implements DailySalesAggregationService {

    private final OrderRepository orderRepository;
    private final DailySalesSummaryRepository dailySalesSummaryRepository;
    private final int chunkSize;

    public DailySalesAggregationServiceImpl(
            OrderRepository orderRepository,
            DailySalesSummaryRepository dailySalesSummaryRepository,
            @Value("${app.batch.daily-sales.chunk-size:500}") int chunkSize) {
        this.orderRepository = orderRepository;
        this.dailySalesSummaryRepository = dailySalesSummaryRepository;
        this.chunkSize = chunkSize;
    }

    @Override
    @Transactional
    public void aggregate(LocalDate salesDate) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = salesDate.atStartOfDay(zone).toInstant();
        Instant end = salesDate.plusDays(1).atStartOfDay(zone).toInstant();

        long totalOrders = 0;
        long processedChunks = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;

        Pageable pageRequest = PageRequest.of(0, chunkSize);
        Page<Order> page;
        do {
            page = orderRepository.findByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdAsc(
                    OrderStatus.PAID,
                    start,
                    end,
                    pageRequest);
            List<Order> orders = page.getContent();
            if (!orders.isEmpty()) {
                processedChunks++;
                totalOrders += orders.size();
                for (Order order : orders) {
                    totalRevenue = totalRevenue.add(order.getTotalAmount());
                }
            }
            pageRequest = page.nextPageable();
        } while (page.hasNext());

        DailySalesSummary summary = dailySalesSummaryRepository.findBySalesDate(salesDate)
                .orElseGet(DailySalesSummary::new);
        summary.setSalesDate(salesDate);
        summary.setPaidOrderCount(totalOrders);
        summary.setTotalRevenue(totalRevenue);
        summary.setProcessedChunks(processedChunks);
        summary.setAggregatedAt(Instant.now());

        dailySalesSummaryRepository.save(summary);
    }
}
