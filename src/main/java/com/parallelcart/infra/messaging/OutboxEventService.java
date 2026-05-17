package com.parallelcart.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parallelcart.domain.model.OutboxEvent;
import com.parallelcart.domain.model.enums.OutboxStatus;
import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import com.parallelcart.infra.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxEventService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void enqueueOrderCreated(OrderCreatedEvent event) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setAggregateType("ORDER");
        outboxEvent.setAggregateId(event.orderId());
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setPayload(toJson(event));
        outboxEventRepository.save(outboxEvent);
    }

    private String toJson(OrderCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox event payload", ex);
        }
    }
}
