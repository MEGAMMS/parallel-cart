package com.parallelcart.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailySalesBatchExecutionResult(
        LocalDate businessDate,
        int chunksProcessed,
        long recordsProcessed,
        boolean completed,
        long lastProcessedOrderId,
        long aggregatedOrderCount,
        BigDecimal aggregatedTotalAmount
) {
}
