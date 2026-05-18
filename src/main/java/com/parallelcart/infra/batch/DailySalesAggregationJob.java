package com.parallelcart.infra.batch;

import com.parallelcart.service.DailySalesAggregationService;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class DailySalesAggregationJob {

    private static final Logger log = LoggerFactory.getLogger(DailySalesAggregationJob.class);

    private final DailySalesAggregationService dailySalesAggregationService;

    public DailySalesAggregationJob(DailySalesAggregationService dailySalesAggregationService) {
        this.dailySalesAggregationService = dailySalesAggregationService;
    }

    @Scheduled(cron = "${app.batch.daily-sales.cron:0 10 0 * * *}")
    public void aggregatePreviousDaySales() {
        LocalDate salesDate = LocalDate.now().minusDays(1);
        dailySalesAggregationService.aggregate(salesDate);
        log.info("daily_sales_aggregation_completed salesDate={}", salesDate);
    }
}
