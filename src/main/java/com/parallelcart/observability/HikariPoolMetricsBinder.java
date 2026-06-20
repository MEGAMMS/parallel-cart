package com.parallelcart.observability;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
public class HikariPoolMetricsBinder {

    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;

    public HikariPoolMetricsBinder(DataSource dataSource, MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void bind() throws Exception {
        HikariDataSource hikariDataSource = dataSource.unwrap(HikariDataSource.class);
        HikariPoolMXBean pool = hikariDataSource.getHikariPoolMXBean();
        if (pool == null) {
            return;
        }
        Tags tags = Tags.of("pool", hikariDataSource.getPoolName());
        meterRegistry.gauge("parallelcart.datasource.connections.active", tags, pool, HikariPoolMXBean::getActiveConnections);
        meterRegistry.gauge("parallelcart.datasource.connections.idle", tags, pool, HikariPoolMXBean::getIdleConnections);
        meterRegistry.gauge("parallelcart.datasource.connections.total", tags, pool, HikariPoolMXBean::getTotalConnections);
        meterRegistry.gauge("parallelcart.datasource.connections.pending", tags, pool, HikariPoolMXBean::getThreadsAwaitingConnection);
    }
}
