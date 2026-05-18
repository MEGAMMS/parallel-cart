package com.parallelcart.service.impl;

import com.parallelcart.domain.model.BatchJobCheckpoint;
import com.parallelcart.domain.model.DailySalesAggregate;
import com.parallelcart.domain.model.Order;
import com.parallelcart.domain.model.enums.BatchJobStatus;
import com.parallelcart.domain.model.enums.OrderStatus;
import com.parallelcart.infra.repository.BatchJobCheckpointRepository;
import com.parallelcart.infra.repository.DailySalesAggregateRepository;
import com.parallelcart.infra.repository.OrderRepository;
import com.parallelcart.service.DailySalesBatchExecutionResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DailySalesBatchService {

    public static final String DAILY_SALES_JOB_NAME = "daily-sales-aggregation";

    private final OrderRepository orderRepository;
    private final DailySalesAggregateRepository dailySalesAggregateRepository;
    private final BatchJobCheckpointRepository batchJobCheckpointRepository;
    private final TransactionTemplate transactionTemplate;
    private final int chunkSize;
    private final ZoneId zoneId;

    public DailySalesBatchService(
            OrderRepository orderRepository,
            DailySalesAggregateRepository dailySalesAggregateRepository,
            BatchJobCheckpointRepository batchJobCheckpointRepository,
            TransactionTemplate transactionTemplate,
            @Value("${app.batch.daily-sales.chunk-size:100}") int chunkSize,
            @Value("${app.batch.daily-sales.zone-id:UTC}") String zoneId
    ) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("app.batch.daily-sales.chunk-size must be > 0");
        }
        this.orderRepository = orderRepository;
        this.dailySalesAggregateRepository = dailySalesAggregateRepository;
        this.batchJobCheckpointRepository = batchJobCheckpointRepository;
        this.transactionTemplate = transactionTemplate;
        this.chunkSize = chunkSize;
        this.zoneId = ZoneId.of(zoneId);
    }

    public DailySalesBatchExecutionResult processBusinessDate(LocalDate businessDate) {
        return processBusinessDateWithLimit(businessDate, Integer.MAX_VALUE);
    }

    public DailySalesBatchExecutionResult processBusinessDateWithLimit(LocalDate businessDate, int maxChunks) {
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("maxChunks must be > 0");
        }
        Instant fromInclusive = businessDate.atStartOfDay(zoneId).toInstant();
        Instant toExclusive = businessDate.plusDays(1).atStartOfDay(zoneId).toInstant();

        int chunksProcessed = 0;
        long recordsProcessed = 0;

        while (chunksProcessed < maxChunks) {
            ChunkProcessState state = transactionTemplate.execute(
                    status -> processChunkTransactional(businessDate, fromInclusive, toExclusive));
            if (state == null) {
                throw new IllegalStateException("Failed to execute daily sales chunk transaction");
            }
            if (state.noWork()) {
                break;
            }
            chunksProcessed++;
            recordsProcessed += state.recordsProcessed();
            if (state.completed()) {
                break;
            }
        }

        BatchJobCheckpoint checkpoint = batchJobCheckpointRepository
                .findByJobNameAndBusinessDate(DAILY_SALES_JOB_NAME, businessDate)
                .orElseGet(() -> createCheckpoint(businessDate));

        DailySalesAggregate aggregate = dailySalesAggregateRepository
                .findByBusinessDate(businessDate)
                .orElseGet(() -> createAggregate(businessDate));

        return new DailySalesBatchExecutionResult(
                businessDate,
                chunksProcessed,
                recordsProcessed,
                checkpoint.getStatus() == BatchJobStatus.COMPLETED,
                checkpoint.getLastProcessedOrderId(),
                aggregate.getOrderCount(),
                aggregate.getTotalAmount()
        );
    }

    private ChunkProcessState processChunkTransactional(LocalDate businessDate, Instant fromInclusive, Instant toExclusive) {
        BatchJobCheckpoint checkpoint = batchJobCheckpointRepository
                .findByJobNameAndBusinessDateForUpdate(DAILY_SALES_JOB_NAME, businessDate)
                .orElseGet(() -> batchJobCheckpointRepository.save(createCheckpoint(businessDate)));

        if (checkpoint.getStatus() == BatchJobStatus.COMPLETED) {
            return new ChunkProcessState(0, true, true);
        }

        List<Order> chunk = orderRepository.findDailySalesChunk(
                OrderStatus.PAID,
                fromInclusive,
                toExclusive,
                checkpoint.getLastProcessedOrderId(),
                PageRequest.of(0, chunkSize, Sort.by(Sort.Direction.ASC, "id"))
        );

        if (chunk.isEmpty()) {
            checkpoint.setStatus(BatchJobStatus.COMPLETED);
            checkpoint.setCompletedAt(Instant.now());
            batchJobCheckpointRepository.save(checkpoint);
            ensureAggregateExists(businessDate);
            return new ChunkProcessState(0, true, true);
        }

        long recordsInChunk = chunk.size();
        long lastOrderId = chunk.get(chunk.size() - 1).getId();
        BigDecimal chunkTotal = chunk.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        DailySalesAggregate aggregate = dailySalesAggregateRepository
                .findByBusinessDate(businessDate)
                .orElseGet(() -> createAggregate(businessDate));

        aggregate.setOrderCount(aggregate.getOrderCount() + recordsInChunk);
        aggregate.setTotalAmount(aggregate.getTotalAmount().add(chunkTotal));
        aggregate.setLastOrderId(lastOrderId);
        dailySalesAggregateRepository.save(aggregate);

        checkpoint.setLastProcessedOrderId(lastOrderId);
        checkpoint.setProcessedRecords(checkpoint.getProcessedRecords() + recordsInChunk);
        boolean completed = chunk.size() < chunkSize;
        if (completed) {
            checkpoint.setStatus(BatchJobStatus.COMPLETED);
            checkpoint.setCompletedAt(Instant.now());
        } else {
            checkpoint.setStatus(BatchJobStatus.RUNNING);
        }
        batchJobCheckpointRepository.save(checkpoint);

        return new ChunkProcessState(recordsInChunk, completed, false);
    }

    private BatchJobCheckpoint createCheckpoint(LocalDate businessDate) {
        BatchJobCheckpoint checkpoint = new BatchJobCheckpoint();
        checkpoint.setJobName(DAILY_SALES_JOB_NAME);
        checkpoint.setBusinessDate(businessDate);
        checkpoint.setLastProcessedOrderId(0L);
        checkpoint.setProcessedRecords(0L);
        checkpoint.setStatus(BatchJobStatus.RUNNING);
        return checkpoint;
    }

    private DailySalesAggregate createAggregate(LocalDate businessDate) {
        DailySalesAggregate aggregate = new DailySalesAggregate();
        aggregate.setBusinessDate(businessDate);
        aggregate.setOrderCount(0L);
        aggregate.setTotalAmount(BigDecimal.ZERO);
        aggregate.setLastOrderId(0L);
        return aggregate;
    }

    private void ensureAggregateExists(LocalDate businessDate) {
        dailySalesAggregateRepository.findByBusinessDate(businessDate)
                .orElseGet(() -> dailySalesAggregateRepository.save(createAggregate(businessDate)));
    }

    private record ChunkProcessState(long recordsProcessed, boolean completed, boolean noWork) {
    }
}
