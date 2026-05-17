package com.parallelcart.infra.messaging.consumers;

import com.parallelcart.domain.model.NotificationLog;
import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import com.parallelcart.infra.repository.NotificationLogRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class NotificationOrderCreatedConsumer {

    private static final String CHANNEL = "EMAIL";

    private final NotificationLogRepository notificationLogRepository;

    public NotificationOrderCreatedConsumer(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = notificationLogRepository;
    }

    @KafkaListener(topics = "${app.kafka.topics.order-created:order.created}", groupId = "notification-workers")
    public void consume(OrderCreatedEvent event) {
        if (notificationLogRepository.findByOrderIdAndChannel(event.orderId(), CHANNEL).isPresent()) {
            return;
        }

        NotificationLog log = new NotificationLog();
        log.setOrderId(event.orderId());
        log.setUserId(event.userId());
        log.setChannel(CHANNEL);
        log.setMessage("Order " + event.orderId() + " confirmed. Total: " + event.totalAmount());
        notificationLogRepository.save(log);
    }
}
