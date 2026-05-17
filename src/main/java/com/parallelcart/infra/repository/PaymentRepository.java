package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findFirstByOrderId(Long orderId);
}
