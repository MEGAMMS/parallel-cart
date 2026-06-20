package com.parallelcart.observability;

import io.micrometer.core.instrument.Tags;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

public class InstrumentedDataSource extends DelegatingDataSource {

    private final BenchmarkMetricsService metricsService;
    private final String dataSourceName;

    public InstrumentedDataSource(DataSource targetDataSource, BenchmarkMetricsService metricsService, String dataSourceName) {
        super(targetDataSource);
        this.metricsService = metricsService;
        this.dataSourceName = dataSourceName;
    }

    @Override
    public Connection getConnection() throws SQLException {
        long startNanos = System.nanoTime();
        try {
            Connection connection = super.getConnection();
            record(startNanos, "success");
            return connection;
        } catch (SQLException ex) {
            record(startNanos, "error");
            throw ex;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        long startNanos = System.nanoTime();
        try {
            Connection connection = super.getConnection(username, password);
            record(startNanos, "success");
            return connection;
        } catch (SQLException ex) {
            record(startNanos, "error");
            throw ex;
        }
    }

    private void record(long startNanos, String status) {
        metricsService.recordDuration(
                "parallelcart.datasource.connection.acquire.duration",
                Tags.of("datasource", dataSourceName, "status", status),
                System.nanoTime() - startNanos);
    }
}
