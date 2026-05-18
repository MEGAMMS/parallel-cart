package com.parallelcart.api.dto;

import java.math.BigDecimal;
import java.io.Serializable;

public record ProductResponse(Long id, String sku, String name, String description, BigDecimal price, boolean active)
        implements Serializable {
}
