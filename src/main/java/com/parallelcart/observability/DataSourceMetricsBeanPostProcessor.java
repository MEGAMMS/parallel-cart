package com.parallelcart.observability;

import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component
public class DataSourceMetricsBeanPostProcessor implements BeanPostProcessor {

    private final BenchmarkMetricsService metricsService;

    public DataSourceMetricsBeanPostProcessor(BenchmarkMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource && !(bean instanceof InstrumentedDataSource)) {
            return new InstrumentedDataSource(dataSource, metricsService, beanName);
        }
        return bean;
    }
}
