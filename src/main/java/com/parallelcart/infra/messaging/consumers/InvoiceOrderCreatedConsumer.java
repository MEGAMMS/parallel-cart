package com.parallelcart.infra.messaging.consumers;

import com.parallelcart.domain.model.Invoice;
import com.parallelcart.infra.messaging.events.OrderCreatedEvent;
import com.parallelcart.infra.repository.InvoiceRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class InvoiceOrderCreatedConsumer {

    private final InvoiceRepository invoiceRepository;

    public InvoiceOrderCreatedConsumer(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    @KafkaListener(topics = "${app.kafka.topics.order-created:order.created}", groupId = "invoice-workers")
    public void consume(OrderCreatedEvent event) {
        if (invoiceRepository.findByOrderId(event.orderId()).isPresent()) {
            return;
        }

        Invoice invoice = new Invoice();
        invoice.setOrderId(event.orderId());
        invoice.setUserId(event.userId());
        invoice.setPaymentId(event.paymentId());
        invoice.setTotalAmount(event.totalAmount());
        invoice.setOrderStatus(event.status());
        invoiceRepository.save(invoice);
    }
}
