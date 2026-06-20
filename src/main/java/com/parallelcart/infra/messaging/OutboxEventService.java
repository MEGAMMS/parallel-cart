package com.parallelcart.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parallelcart.domain.model.OutboxEvent;
import com.parallelcart.domain.model.enums.OutboxStatus;
import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import com.parallelcart.infra.repository.OutboxEventRepository;
import com.parallelcart.observability.BenchmarkMetricsService;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Service;

@Service
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final BenchmarkMetricsService metricsService;

    public OutboxEventService(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            BenchmarkMetricsService metricsService) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public void enqueueOrderCreated(OrderCreatedEvent event) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateType("ORDER");
        outboxEvent.setAggregateId(event.orderId());
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setPayload(toJson(event));
        metricsService.time(
                "parallelcart.outbox.write.duration",
                Tags.of("event_type", "ORDER_CREATED"),
                () -> outboxEventRepository.save(outboxEvent));
        metricsService.increment("parallelcart.outbox.write.total", Tags.of("event_type", "ORDER_CREATED"));
    }

    private String toJson(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event payload", ex);
        }
    }
}
