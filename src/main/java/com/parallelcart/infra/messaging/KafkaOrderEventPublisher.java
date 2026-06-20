package com.parallelcart.infra.messaging;

import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import com.parallelcart.observability.BenchmarkMetricsService;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class KafkaOrderEventPublisher implements OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String orderCreatedTopic;
    private final BenchmarkMetricsService metricsService;

    public KafkaOrderEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.order-created:order.created}") String orderCreatedTopic,
            BenchmarkMetricsService metricsService
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.orderCreatedTopic = orderCreatedTopic;
        this.metricsService = metricsService;
    }

    @Override
    public void publishOrderCreated(OrderCreatedEvent event) {
        Tags tags = Tags.of("topic", orderCreatedTopic, "event_type", "ORDER_CREATED");
        long startedNanos = System.nanoTime();
        kafkaTemplate.send(orderCreatedTopic, String.valueOf(event.orderId()), event)
                .whenComplete((result, ex) -> {
                    String status = ex == null ? "success" : "error";
                    metricsService.recordDuration(
                            "parallelcart.kafka.publish.ack.duration",
                            tags.and("status", status),
                            System.nanoTime() - startedNanos);
                    metricsService.increment("parallelcart.kafka.publish.total", tags.and("status", status));
                });
        metricsService.recordDuration(
                "parallelcart.kafka.publish.enqueue.duration",
                tags,
                System.nanoTime() - startedNanos);
    }
}
