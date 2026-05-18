package com.parallelcart.infra.messaging;

import com.parallelcart.service.DailySalesBatchExecutionResult;
import com.parallelcart.service.impl.DailySalesBatchService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DailySalesBatchJob {

    private static final Logger log = LoggerFactory.getLogger(DailySalesBatchJob.class);

    private final DailySalesBatchService dailySalesBatchService;
    private final ZoneId zoneId;
    private final long defaultOffsetDays;

    public DailySalesBatchJob(
            DailySalesBatchService dailySalesBatchService,
            @Value("${app.batch.daily-sales.zone-id:UTC}") String zoneId,
            @Value("${app.batch.daily-sales.default-offset-days:1}") long defaultOffsetDays
    ) {
        this.dailySalesBatchService = dailySalesBatchService;
        this.zoneId = ZoneId.of(zoneId);
        this.defaultOffsetDays = defaultOffsetDays;
    }

    @Scheduled(
            cron = "${app.batch.daily-sales.cron:0 0 1 * * *}",
            zone = "${app.batch.daily-sales.zone-id:UTC}"
    )
    public void runDailySalesAggregation() {
        LocalDate businessDate = LocalDate.now(zoneId).minusDays(defaultOffsetDays);
        DailySalesBatchExecutionResult result = dailySalesBatchService.processBusinessDate(businessDate);
        log.info(
                "daily_sales_batch_result businessDate={} chunksProcessed={} recordsProcessed={} completed={} lastProcessedOrderId={} aggregatedOrderCount={} aggregatedTotalAmount={}",
                result.businessDate(),
                result.chunksProcessed(),
                result.recordsProcessed(),
                result.completed(),
                result.lastProcessedOrderId(),
                result.aggregatedOrderCount(),
                result.aggregatedTotalAmount()
        );
    }
}
