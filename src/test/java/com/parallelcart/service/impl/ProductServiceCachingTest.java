package com.parallelcart.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.parallelcart.api.dto.ProductResponse;
import com.parallelcart.infra.repository.ProductRepository;
import com.parallelcart.service.ProductService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig
@Import(ProductServiceCachingTest.TestConfig.class)
class ProductServiceCachingTest {

    @TestConfiguration
    @EnableCaching
    static class TestConfig {
        @Bean
        ProductServiceImpl productService(ProductRepository productRepository) {
            return new ProductServiceImpl(productRepository);
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("products");
        }
    }

    @Autowired
    private ProductService productService;

    @MockBean
    private ProductRepository productRepository;

    @Test
    void getProductUsesCacheForRepeatedRead() {
        ProductResponse product = new ProductResponse(
                1L,
                "SKU-1",
                "Sample Product",
                "Sample Desc",
                BigDecimal.TEN,
                true);

        when(productRepository.findResponseById(1L)).thenReturn(Optional.of(product));

        ProductResponse first = productService.getProduct(1L);
        ProductResponse second = productService.getProduct(1L);

        assertEquals(first, second);
        verify(productRepository, times(1)).findResponseById(1L);
    }
}
