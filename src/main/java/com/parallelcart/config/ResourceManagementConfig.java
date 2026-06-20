package com.parallelcart.config;

import com.parallelcart.observability.BenchmarkMetricsService;
import com.parallelcart.observability.MetricsTaskDecorator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ResourceManagementConfig {

    private final BenchmarkMetricsService metricsService;
    private final MeterRegistry meterRegistry;

    public ResourceManagementConfig(BenchmarkMetricsService metricsService, MeterRegistry meterRegistry) {
        this.metricsService = metricsService;
        this.meterRegistry = meterRegistry;
    }

    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor(
            @Value("${app.executors.async.core-pool-size:4}") int corePoolSize,
            @Value("${app.executors.async.max-pool-size:8}") int maxPoolSize,
            @Value("${app.executors.async.queue-capacity:100}") int queueCapacity,
            @Value("${app.executors.async.keep-alive-seconds:60}") int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("app-async-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new MetricsTaskDecorator(metricsService, "applicationTaskExecutor"));
        executor.initialize();
        bindExecutorGauges("applicationTaskExecutor", executor.getThreadPoolExecutor());
        return executor;
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${app.executors.scheduler.pool-size:2}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("app-scheduler-");
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        scheduler.initialize();
        bindExecutorGauges("taskScheduler", scheduler.getScheduledThreadPoolExecutor());
        return scheduler;
    }

    private void bindExecutorGauges(String executorName, ThreadPoolExecutor executor) {
        Tags tags = Tags.of("executor", executorName);
        meterRegistry.gauge("parallelcart.executor.active", tags, executor, ThreadPoolExecutor::getActiveCount);
        meterRegistry.gauge("parallelcart.executor.pool.size", tags, executor, ThreadPoolExecutor::getPoolSize);
        meterRegistry.gauge("parallelcart.executor.queue.size", tags, executor, current -> current.getQueue().size());
        meterRegistry.gauge("parallelcart.executor.completed", tags, executor, ThreadPoolExecutor::getCompletedTaskCount);
    }
}
