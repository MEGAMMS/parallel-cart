package com.parallelcart.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "daily_sales_summary")
public class DailySalesSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate salesDate;

    @Column(nullable = false)
    private Long paidOrderCount;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalRevenue;

    @Column(nullable = false)
    private Long processedChunks;

    @Column(nullable = false)
    private Instant aggregatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public LocalDate getSalesDate() {
        return salesDate;
    }

    public void setSalesDate(LocalDate salesDate) {
        this.salesDate = salesDate;
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

    public Instant getAggregatedAt() {
        return aggregatedAt;
    }

    public void setAggregatedAt(Instant aggregatedAt) {
        this.aggregatedAt = aggregatedAt;
    }
}
