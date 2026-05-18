package com.parallelcart.domain.model;

import com.parallelcart.domain.model.enums.BatchJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "batch_job_checkpoints",
        uniqueConstraints = @UniqueConstraint(name = "uk_batch_checkpoint_job_date", columnNames = {"job_name", "business_date"})
)
public class BatchJobCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false)
    private String jobName;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "last_processed_order_id", nullable = false)
    private Long lastProcessedOrderId;

    @Column(name = "processed_records", nullable = false)
    private Long processedRecords;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchJobStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = BatchJobStatus.RUNNING;
        }
        if (lastProcessedOrderId == null) {
            lastProcessedOrderId = 0L;
        }
        if (processedRecords == null) {
            processedRecords = 0L;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public void setBusinessDate(LocalDate businessDate) {
        this.businessDate = businessDate;
    }

    public Long getLastProcessedOrderId() {
        return lastProcessedOrderId;
    }

    public void setLastProcessedOrderId(Long lastProcessedOrderId) {
        this.lastProcessedOrderId = lastProcessedOrderId;
    }

    public Long getProcessedRecords() {
        return processedRecords;
    }

    public void setProcessedRecords(Long processedRecords) {
        this.processedRecords = processedRecords;
    }

    public BatchJobStatus getStatus() {
        return status;
    }

    public void setStatus(BatchJobStatus status) {
        this.status = status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
