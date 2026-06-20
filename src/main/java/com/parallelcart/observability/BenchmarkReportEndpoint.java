package com.parallelcart.observability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "benchmarkreport")
public class BenchmarkReportEndpoint {

    private final BottleneckAnalysisService bottleneckAnalysisService;

    public BenchmarkReportEndpoint(BottleneckAnalysisService bottleneckAnalysisService) {
        this.bottleneckAnalysisService = bottleneckAnalysisService;
    }

    @ReadOperation
    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("analysis", bottleneckAnalysisService.analyze());
        report.put("interpretation", "Rankings are estimated from measured timer totals. Validate conclusions by rerunning multiple scenarios.");
        return report;
    }
}
