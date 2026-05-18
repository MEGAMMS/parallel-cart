package com.parallelcart.service.impl;

import com.parallelcart.api.dto.ProductResponse;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.service.ProductService;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    public ProductServiceImpl(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Cacheable(cacheNames = "products", key = "'all'")
    public List<ProductResponse> listProducts() {
        return productRepository.findAllResponses();
    }

    @Override
    @Cacheable(cacheNames = "products", key = "'id:' + #id")
    public ProductResponse getProduct(Long id) {
        return productRepository.findResponseById(id)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }
}
