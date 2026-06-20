package com.parallelcart.service.impl;

import com.parallelcart.api.dto.ProductResponse;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.observability.BenchmarkMetricsService;
import com.parallelcart.service.ProductService;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final BenchmarkMetricsService metricsService;

    public ProductServiceImpl(ProductRepository productRepository, BenchmarkMetricsService metricsService) {
        this.productRepository = productRepository;
        this.metricsService = metricsService;
    }

    @Override
    @Cacheable(cacheNames = "products", key = "'all'")
    public List<ProductResponse> listProducts() {
        return metricsService.time(
                "parallelcart.cache.backing_load.duration",
                Tags.of("cache", "products", "operation", "list"),
                productRepository::findAllResponses);
    }

    @Override
    @Cacheable(cacheNames = "products", key = "'id:' + #id")
    public ProductResponse getProduct(Long id) {
        return metricsService.time(
                        "parallelcart.cache.backing_load.duration",
                        Tags.of("cache", "products", "operation", "get_by_id"),
                        () -> productRepository.findResponseById(id))
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }
}
