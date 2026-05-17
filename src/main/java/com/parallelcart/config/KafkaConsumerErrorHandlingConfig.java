package com.parallelcart.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Profile("!test")
public class KafkaConsumerErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerErrorHandlingConfig.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaOperations<Object, Object> kafkaTemplate,
            @Value("${app.kafka.retry.interval-ms:1000}") long retryIntervalMs,
            @Value("${app.kafka.retry.max-attempts:3}") long maxAttempts,
            @Value("${app.kafka.topics.order-created-dlq:order.created.dlq}") String orderCreatedDlqTopic) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, (record, ex) -> {
            String dlqTopic = resolveDlqTopic(record, orderCreatedDlqTopic);
            log.error(
                    "kafka_consumer_dlq topic={} partition={} offset={} key={} dlqTopic={} errorType={} errorMessage={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    dlqTopic,
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return new TopicPartition(dlqTopic, record.partition());
        });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(retryIntervalMs, Math.max(0, maxAttempts - 1)));

        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> log.warn(
                "kafka_consumer_retry topic={} partition={} offset={} key={} attempt={} errorType={} errorMessage={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                deliveryAttempt,
                ex.getClass().getSimpleName(),
                ex.getMessage()));

        return errorHandler;
    }

    private String resolveDlqTopic(ConsumerRecord<?, ?> record, String orderCreatedDlqTopic) {
        if ("order.created".equals(record.topic())) {
            return orderCreatedDlqTopic;
        }
        return record.topic() + ".dlq";
    }
}
