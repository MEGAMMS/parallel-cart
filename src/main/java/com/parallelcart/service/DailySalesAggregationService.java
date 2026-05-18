package com.parallelcart.service;

import java.time.LocalDate;

public interface DailySalesAggregationService {
    void aggregate(LocalDate salesDate);
}
