package com.parallelcart.observability;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class CacheMetricsBeanPostProcessor implements BeanPostProcessor {

    private final BenchmarkMetricsService metricsService;
    private final Environment environment;

    public CacheMetricsBeanPostProcessor(BenchmarkMetricsService metricsService, Environment environment) {
        this.metricsService = metricsService;
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof CacheManager cacheManager && !(bean instanceof InstrumentedCacheManager)) {
            boolean cacheDisabled = "none".equalsIgnoreCase(environment.getProperty("spring.cache.type", ""));
            return new InstrumentedCacheManager(cacheManager, metricsService, cacheDisabled);
        }
        return bean;
    }
}
