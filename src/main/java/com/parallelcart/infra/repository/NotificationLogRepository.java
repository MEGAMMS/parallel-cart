package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.NotificationLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    Optional<NotificationLog> findByOrderIdAndChannel(Long orderId, String channel);
}
