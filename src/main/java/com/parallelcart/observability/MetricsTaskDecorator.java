package com.parallelcart.observability;

import io.micrometer.core.instrument.Tags;
import org.springframework.core.task.TaskDecorator;

public class MetricsTaskDecorator implements TaskDecorator {

    private final BenchmarkMetricsService metricsService;
    private final String executorName;

    public MetricsTaskDecorator(BenchmarkMetricsService metricsService, String executorName) {
        this.metricsService = metricsService;
        this.executorName = executorName;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        long submittedNanos = System.nanoTime();
        return () -> {
            Tags tags = Tags.of("executor", executorName);
            long startedNanos = System.nanoTime();
            metricsService.recordDuration(
                    "parallelcart.executor.queue.wait.duration",
                    tags,
                    startedNanos - submittedNanos);
            try {
                runnable.run();
                metricsService.increment("parallelcart.executor.task.total", tags.and("status", "success"));
            } catch (RuntimeException ex) {
                metricsService.increment("parallelcart.executor.task.total", tags.and("status", "error"));
                throw ex;
            } finally {
                metricsService.recordDuration(
                        "parallelcart.executor.task.duration",
                        tags,
                        System.nanoTime() - startedNanos);
            }
        };
    }
}
