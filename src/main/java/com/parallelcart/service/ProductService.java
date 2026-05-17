package com.parallelcart.service;

import com.parallelcart.api.dto.ProductResponse;
import java.util.List;

public interface ProductService {
    List<ProductResponse> listProducts();

    ProductResponse getProduct(Long id);
}
