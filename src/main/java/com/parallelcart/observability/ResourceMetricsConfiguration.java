package com.parallelcart.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ResourceMetricsConfiguration {

    private final MeterRegistry meterRegistry;
    private JvmGcMetrics jvmGcMetrics;

    public ResourceMetricsConfiguration(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void bindResourceMetrics() {
        bindIfMissing("jvm.memory.used", new JvmMemoryMetrics());
        jvmGcMetrics = new JvmGcMetrics();
        bindIfMissing("jvm.gc.memory.allocated", jvmGcMetrics);
        bindIfMissing("jvm.threads.live", new JvmThreadMetrics());
        bindIfMissing("jvm.classes.loaded", new ClassLoaderMetrics());
        bindIfMissing("system.cpu.count", new ProcessorMetrics());
        bindIfMissing("process.uptime", new UptimeMetrics());
    }

    @PreDestroy
    void closeResourceMetrics() {
        if (jvmGcMetrics != null) {
            jvmGcMetrics.close();
        }
    }

    private void bindIfMissing(String meterName, MeterBinder meterBinder) {
        if (meterRegistry.find(meterName).meters().isEmpty()) {
            meterBinder.bindTo(meterRegistry);
        }
    }
}
