package com.parallelcart.observability;

import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "bottlenecks")
public class BottleneckAnalysisEndpoint {

    private final BottleneckAnalysisService bottleneckAnalysisService;

    public BottleneckAnalysisEndpoint(BottleneckAnalysisService bottleneckAnalysisService) {
        this.bottleneckAnalysisService = bottleneckAnalysisService;
    }

    @ReadOperation
    public Map<String, Object> bottlenecks() {
        return bottleneckAnalysisService.analyze();
    }
}
