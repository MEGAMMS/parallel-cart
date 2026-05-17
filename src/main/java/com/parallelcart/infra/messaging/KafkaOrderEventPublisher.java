package com.parallelcart.infra.messaging;

import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class KafkaOrderEventPublisher implements OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String orderCreatedTopic;

    public KafkaOrderEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${app.kafka.topics.order-created:order.created}") String orderCreatedTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.orderCreatedTopic = orderCreatedTopic;
    }

    @Override
    public void publishOrderCreated(OrderCreatedEvent event) {
        kafkaTemplate.send(orderCreatedTopic, String.valueOf(event.orderId()), event);
    }
}
