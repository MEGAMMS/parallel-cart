package com.parallelcart.domain.model;

import com.parallelcart.domain.model.enums.BatchRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_sales_checkpoint")
public class DailySalesCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate salesDate;

    @Column(nullable = false)
    private Long lastProcessedOrderId = 0L;

    @Column(nullable = false)
    private Long paidOrderCount = 0L;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    @Column(nullable = false)
    private Long processedChunks = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BatchRunStatus status = BatchRunStatus.IN_PROGRESS;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public LocalDate getSalesDate() {
        return salesDate;
    }

    public void setSalesDate(LocalDate salesDate) {
        this.salesDate = salesDate;
    }

    public Long getLastProcessedOrderId() {
        return lastProcessedOrderId;
    }

    public void setLastProcessedOrderId(Long lastProcessedOrderId) {
        this.lastProcessedOrderId = lastProcessedOrderId;
    }

    public Long getPaidOrderCount() {
        return paidOrderCount;
    }

    public void setPaidOrderCount(Long paidOrderCount) {
        this.paidOrderCount = paidOrderCount;
    }

    public BigDecimal getTotalRevenue() {
        return totalRevenue;
    }

    public void setTotalRevenue(BigDecimal totalRevenue) {
        this.totalRevenue = totalRevenue;
    }

    public Long getProcessedChunks() {
        return processedChunks;
    }

    public void setProcessedChunks(Long processedChunks) {
        this.processedChunks = processedChunks;
    }

    public BatchRunStatus getStatus() {
        return status;
    }

    public void setStatus(BatchRunStatus status) {
        this.status = status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
