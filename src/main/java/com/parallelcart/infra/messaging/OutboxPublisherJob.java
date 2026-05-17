package com.parallelcart.infra.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parallelcart.domain.model.OutboxEvent;
import com.parallelcart.domain.model.enums.OutboxStatus;
import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import com.parallelcart.infra.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
public class OutboxPublisherJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public OutboxPublisherJob(
            OutboxEventRepository outboxEventRepository,
            OrderEventPublisher orderEventPublisher,
            ObjectMapper objectMapper,
            @Value("${app.outbox.max-publish-attempts:5}") int maxAttempts) {
        this.outboxEventRepository = outboxEventRepository;
        this.orderEventPublisher = orderEventPublisher;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.outbox.publisher.fixed-delay-ms:2000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for (OutboxEvent event : pendingEvents) {
            publishSingle(event);
        }
    }

    private void publishSingle(OutboxEvent event) {
        event.setPublishAttempts(event.getPublishAttempts() + 1);
        try {
            if ("ORDER_CREATED".equals(event.getEventType())) {
                OrderCreatedEvent payload = objectMapper.readValue(event.getPayload(), OrderCreatedEvent.class);
                orderEventPublisher.publishOrderCreated(payload);
            } else {
                throw new IllegalStateException("Unsupported outbox event type: " + event.getEventType());
            }
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setLastError(null);
        } catch (Exception ex) {
            event.setLastError(shortMessage(ex));
            if (event.getPublishAttempts() >= maxAttempts) {
                event.setStatus(OutboxStatus.FAILED);
            }
            log.error(
                    "outbox_publish_failed eventId={} eventType={} attempts={} status={} errorType={} errorMessage={}",
                    event.getId(),
                    event.getEventType(),
                    event.getPublishAttempts(),
                    event.getStatus(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
        outboxEventRepository.save(event);
    }

    private String shortMessage(Exception ex) {
        String msg = ex instanceof JsonProcessingException jsonEx
                ? "JSON_PARSE_ERROR: " + jsonEx.getOriginalMessage()
                : ex.getMessage();
        if (msg == null) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }
}
