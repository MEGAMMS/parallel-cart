package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.BatchJobCheckpoint;
import java.time.LocalDate;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BatchJobCheckpointRepository extends JpaRepository<BatchJobCheckpoint, Long> {
    Optional<BatchJobCheckpoint> findByJobNameAndBusinessDate(String jobName, LocalDate businessDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BatchJobCheckpoint c where c.jobName = :jobName and c.businessDate = :businessDate")
    Optional<BatchJobCheckpoint> findByJobNameAndBusinessDateForUpdate(String jobName, LocalDate businessDate);
}
